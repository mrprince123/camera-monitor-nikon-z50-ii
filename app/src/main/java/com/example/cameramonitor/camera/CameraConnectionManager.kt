package com.example.cameramonitor.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.cameramonitor.AppLog
import com.example.cameramonitor.camera.usb.UsbDeviceDetector
import com.example.cameramonitor.camera.usb.UsbPermissionManager
import com.example.cameramonitor.camera.usb.ptp.PtpUsbCameraBackend
import com.example.cameramonitor.camera.usb.uvc.UvcCameraBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Universal Camera Connection Orchestrator for CamMonitor.
 * Manages device detection, OS permission negotiation, backend instantiation
 * (PTP Remote Control vs UVC Streaming), and session lifecycle safety.
 */
class CameraConnectionManager(
    private val context: Context
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val detector = UsbDeviceDetector(usbManager)
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val connectionMutex = Mutex()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Disconnected)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _activeBackend = MutableStateFlow<CameraBackend?>(null)
    val activeBackend: StateFlow<CameraBackend?> = _activeBackend.asStateFlow()

    private val _properties = MutableStateFlow<Map<CameraPropertyType, CameraPropertyValue>>(emptyMap())
    val properties: StateFlow<Map<CameraPropertyType, CameraPropertyValue>> = _properties.asStateFlow()

    private val _isMovieRecording = MutableStateFlow(false)
    val isMovieRecording: StateFlow<Boolean> = _isMovieRecording.asStateFlow()

    private val _afTargetState = MutableStateFlow<AfTargetState?>(null)
    val afTargetState: StateFlow<AfTargetState?> = _afTargetState.asStateFlow()

    @Volatile private var currentSessionId = 0L
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var activeSurfaceTexture: SurfaceTexture? = null

    private var backendStateJob: Job? = null
    private var backendPropsJob: Job? = null
    private var backendRecJob: Job? = null
    private var backendAfJob: Job? = null
    private var isReceiverRegistered = false

    private val permissionManager = UsbPermissionManager(context, usbManager) { device, granted, sessionId ->
        scope.launch {
            handlePermissionResult(device, granted, sessionId)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let { handleDeviceAttached(it, source = "BROADCAST") }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let { handleDeviceDetached(it) }
                }
            }
        }
    }

    fun start() {
        if (isReceiverRegistered) return
        permissionManager.start()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isReceiverRegistered = true
        AppLog.i("ConnManager", "CameraConnectionManager started, receivers registered")

        scanForConnectedDevices()
    }

    fun stop() {
        AppLog.i("ConnManager", "Stopping CameraConnectionManager...")
        currentSessionId++
        permissionManager.stop()

        if (isReceiverRegistered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }

        scope.launch {
            disconnectActiveBackend("manager stopped")
            activeDevice = null
            _cameraState.value = CameraState.Disconnected
        }
    }

    fun setPreviewSurface(surfaceTexture: SurfaceTexture?) {
        this.activeSurfaceTexture = surfaceTexture
        _activeBackend.value?.setPreviewSurface(surfaceTexture)
    }

    fun onNewIntentAttachedDevice(device: UsbDevice?) {
        if (device != null) {
            handleDeviceAttached(device, source = "NEW_INTENT")
        } else {
            scanForConnectedDevices()
        }
    }

    fun onResumeCheck() {
        val dev = activeDevice ?: run {
            if (_cameraState.value is CameraState.Disconnected) scanForConnectedDevices()
            return
        }

        val state = _cameraState.value
        if (state is CameraState.PermissionPending || state is CameraState.DeviceDetected) {
            if (permissionManager.hasPermission(dev)) {
                AppLog.i("ConnManager", "[Session #$currentSessionId] Permission verified on resume for ${dev.deviceName}")
                scope.launch {
                    connectDevice(dev, currentSessionId)
                }
            }
        }
    }

    fun scanForConnectedDevices() {
        val candidate = detector.findFirstSupportedDevice()
        if (candidate != null) {
            handleDeviceAttached(candidate.device, source = "SCAN")
        } else {
            AppLog.d("ConnManager", "Scan complete: No supported camera device connected.")
        }
    }

    private fun handleDeviceAttached(device: UsbDevice, source: String) {
        val inspection = detector.inspectDevice(device)
        if (inspection.category == UsbDeviceDetector.DeviceCategory.UNSUPPORTED) {
            AppLog.w("ConnManager", "[$source] Device ${device.deviceName} unsupported (${inspection.interfaceSummary})")
            return
        }

        // Deduplication: prevent re-initializing an already active or connecting device
        val currState = _cameraState.value
        if (activeDevice?.deviceId == device.deviceId &&
            currState !is CameraState.Disconnected &&
            currState !is CameraState.Error) {
            AppLog.i("ConnManager", "[$source] Ignoring duplicate attach for ${device.deviceName} (state=$currState, session=$currentSessionId)")
            return
        }

        val sessionId = ++currentSessionId
        activeDevice = device
        val camDev = detector.toCameraDevice(device, inspection.category)
        _cameraState.value = CameraState.DeviceDetected(camDev.displayName, camDev.vidPidHex)
        AppLog.i("ConnManager", "[$source][Session #$sessionId] Attached: ${camDev.displayName} [${inspection.category}]")

        if (permissionManager.hasPermission(device)) {
            AppLog.i("ConnManager", "[Session #$sessionId] Permission already granted for ${device.deviceName}")
            scope.launch {
                connectDevice(device, sessionId)
            }
        } else {
            _cameraState.value = CameraState.PermissionPending(camDev.displayName)
            permissionManager.requestPermission(device, sessionId)
        }
    }

    private suspend fun handlePermissionResult(device: UsbDevice, granted: Boolean, sessionId: Long) {
        if (sessionId != currentSessionId) {
            AppLog.w("ConnManager", "Ignoring stale permission callback for session #$sessionId (curr=$currentSessionId)")
            return
        }

        if (granted) {
            AppLog.i("ConnManager", "[Session #$sessionId] Permission granted for ${device.deviceName}")
            connectDevice(device, sessionId)
        } else {
            AppLog.w("ConnManager", "[Session #$sessionId] Permission denied by user for ${device.deviceName}")
            _cameraState.value = CameraState.Error("USB permission denied by user", recoverable = true)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        AppLog.w("ConnManager", "Device detached: ${device.deviceName} (active=${activeDevice?.deviceName}, session=$currentSessionId)")
        if (activeDevice?.deviceId == device.deviceId) {
            currentSessionId++
            scope.launch {
                disconnectActiveBackend("device detached")
                activeDevice = null
                _cameraState.value = CameraState.Disconnected
            }
        }
    }

    private suspend fun connectDevice(device: UsbDevice, sessionId: Long) = connectionMutex.withLock {
        if (sessionId != currentSessionId) return@withLock

        disconnectActiveBackend("initiating new connection")

        val inspection = detector.inspectDevice(device)
        AppLog.i("ConnManager", "[Session #$sessionId] Selecting backend for ${device.deviceName} [${inspection.category}]...")

        // Backend Selection Strategy:
        // 1. If device exposes PTP (or composite UVC+PTP on Nikon), prefer PTP for remote shutter & live view!
        // 2. If PTP fails or device is UVC-only (e.g. webcam mode or HDMI capture), use UVC backend!
        val backend: CameraBackend = when (inspection.category) {
            UsbDeviceDetector.DeviceCategory.PTP_CAMERA,
            UsbDeviceDetector.DeviceCategory.COMPOSITE_UVC_PTP -> {
                AppLog.i("ConnManager", "[Session #$sessionId] Instantiating PtpUsbCameraBackend...")
                PtpUsbCameraBackend(context)
            }
            UsbDeviceDetector.DeviceCategory.UVC_VIDEO -> {
                val connType = detector.toCameraDevice(device, inspection.category).connectionType
                AppLog.i("ConnManager", "[Session #$sessionId] Instantiating UvcCameraBackend ($connType)...")
                UvcCameraBackend(context, connType)
            }
            else -> {
                _cameraState.value = CameraState.Error("Unsupported camera mode")
                return@withLock
            }
        }

        _activeBackend.value = backend

        // Forward backend preview surface
        backend.setPreviewSurface(activeSurfaceTexture)

        // Bind backend state to connection manager state flow
        backendStateJob?.cancel()
        backendStateJob = scope.launch {
            backend.state.collect { st ->
                if (sessionId == currentSessionId) {
                    _cameraState.value = st
                }
            }
        }

        backendPropsJob?.cancel()
        backendPropsJob = scope.launch {
            backend.properties.collect { p -> _properties.value = p }
        }

        backendRecJob?.cancel()
        backendRecJob = scope.launch {
            backend.isMovieRecording.collect { rec -> _isMovieRecording.value = rec }
        }

        backendAfJob?.cancel()
        backendAfJob = scope.launch {
            backend.afTargetState.collect { af -> _afTargetState.value = af }
        }

        // Connect backend
        val result = backend.connect(device, sessionId)
        if (result.isFailure) {
            AppLog.e("ConnManager", "[Session #$sessionId] Connection failed: ${result.exceptionOrNull()?.message}")
            // Fallback: If composite device failed on PTP, attempt fallback to UVC
            if (inspection.category == UsbDeviceDetector.DeviceCategory.COMPOSITE_UVC_PTP) {
                AppLog.w("ConnManager", "[Session #$sessionId] PTP connection failed on composite device; falling back to UvcCameraBackend...")
                val uvcBackend = UvcCameraBackend(context, CameraDevice.ConnectionType.USB_UVC)
                _activeBackend.value = uvcBackend
                uvcBackend.setPreviewSurface(activeSurfaceTexture)
                backendStateJob?.cancel()
                backendStateJob = scope.launch {
                    uvcBackend.state.collect { st ->
                        if (sessionId == currentSessionId) {
                            _cameraState.value = st
                        }
                    }
                }
                backendPropsJob?.cancel()
                backendRecJob?.cancel()
                backendAfJob?.cancel()
                uvcBackend.connect(device, sessionId)
            }
        }
    }

    private suspend fun disconnectActiveBackend(reason: String) {
        backendStateJob?.cancel()
        backendStateJob = null
        backendPropsJob?.cancel()
        backendPropsJob = null
        backendRecJob?.cancel()
        backendRecJob = null
        backendAfJob?.cancel()
        backendAfJob = null

        val b = _activeBackend.value
        _activeBackend.value = null
        b?.disconnect(reason)

        _properties.value = emptyMap()
        _isMovieRecording.value = false
        _afTargetState.value = null
    }

    // ── Remote Controls Proxies ────────────────────────────────────────────

    suspend fun triggerShutter(): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.triggerShutter()
    }

    suspend fun startMovieRecording(): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.startMovieRecording()
    }

    suspend fun stopMovieRecording(): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.stopMovieRecording()
    }

    suspend fun triggerAutofocus(): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.triggerAutofocus()
    }

    suspend fun setTouchFocusPoint(point: PointF): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.setTouchFocusPoint(point)
    }

    suspend fun setProperty(property: CameraPropertyType, value: CameraPropertyValue): Result<Unit> {
        val b = _activeBackend.value ?: return Result.failure(IllegalStateException("No active camera"))
        return b.setProperty(property, value)
    }
}

