package com.example.cameramonitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import java.nio.ByteBuffer

/**
 * Centralized USB Connection & UVC Stream Manager for CamMonitor.
 *
 * Implements a strict, single-owner state machine:
 *   DISCONNECTED
 *     → USB_DETECTED
 *     → USB_PERMISSION_GRANTED
 *     → USB_DEVICE_OPENED
 *     → UVC_INITIALIZED
 *     → PREVIEW_STARTED
 *     → FRAMES_RECEIVED
 *     → PREVIEW_RENDERING
 *     (or ERROR)
 *
 * Key guarantees:
 *  1. Tracks connection attempts via unique sessionId to reject stale/delayed callbacks.
 *  2. Type-safe Parcelable extraction for Android 13–16 (API 33–36).
 *  3. Explicit PendingIntent with FLAG_MUTABLE and synchronous resume fallback.
 *  4. Native frame callback (IFrameCallback) verifying actual video frame arrival from camera.
 *  5. Explicit Surface lifecycle management: clears native ANativeWindow before release.
 *  6. Single active session; ignores duplicate attach events and preserves PTP (Class 6).
 *  7. Exposes live diagnostic metrics (fps, total frames, last frame bytes, surface status).
 */
class UsbCameraManager(
    private val context: Context,
    private val stateListener: (oldState: UsbConnectionState, newState: UsbConnectionState) -> Unit
) {

    enum class UsbConnectionState {
        DISCONNECTED,
        USB_DETECTED,
        PERMISSION_PENDING,
        USB_PERMISSION_GRANTED,
        USB_DEVICE_OPENED,
        UVC_INITIALIZED,
        PREVIEW_STARTED,
        FRAMES_RECEIVED,
        PREVIEW_RENDERING,
        ERROR
    }

    data class DiagnosticInfo(
        val state: UsbConnectionState,
        val deviceName: String,
        val vidPid: String,
        val permissionGranted: Boolean,
        val resolutionFormat: String,
        val totalFrames: Long,
        val fps: Int,
        val lastFrameTimeMs: Long,
        val lastFrameSizeBytes: Int,
        val surfaceStatus: String,
        val lastError: String
    )

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.cameramonitor.USB_PERMISSION"
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
        const val USB_CLASS_VIDEO = 14
        const val USB_CLASS_PTP = 6
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    var state = UsbConnectionState.DISCONNECTED
        private set

    // Session token to invalidate stale/delayed callbacks
    @Volatile
    private var currentSessionId = 0L

    // Active connection references
    private var activeDevice: UsbDevice? = null
    private var activeCtrlBlock: USBMonitor.UsbControlBlock? = null
    private var uvcCamera: UVCCamera? = null
    private var activeSurfaceTexture: SurfaceTexture? = null
    private var activeSurface: Surface? = null

    // Diagnostics data
    @Volatile var totalFrames = 0L; private set
    @Volatile var currentFps = 0; private set
    @Volatile var lastFrameTimeMs = 0L; private set
    @Volatile var lastFrameSizeBytes = 0; private set
    @Volatile var lastError = ""; private set
    @Volatile var resolutionFormat = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG"; private set
    @Volatile var surfaceStatus = "Not initialized"; private set

    private var framesThisSecond = 0
    private var lastFpsCalcTime = 0L
    var diagnosticsListener: ((DiagnosticInfo) -> Unit)? = null

    // USBMonitor used ONLY as factory for UsbControlBlock (dummy, never registered)
    private val dummyMonitor: USBMonitor = USBMonitor.getInstance(context).apply {
        setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {}
            override fun onDetach(device: UsbDevice?) {}
            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
            override fun onCancel(device: UsbDevice?) {}
        })
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            AppLog.i("UsbManager", "BroadcastReceiver onReceive() action=$action")

            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (action) {
                ACTION_USB_PERMISSION -> handlePermissionResult(intent, device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let { handleDeviceAttached(it, source = "BROADCAST") }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let { handleDeviceDetached(it) }
                }
            }
        }
    }

    private var isReceiverRegistered = false

    fun start() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isReceiverRegistered = true
        AppLog.i("UsbManager", "UsbCameraManager started, receiver registered (RECEIVER_NOT_EXPORTED)")

        // Initial scan for already connected devices
        scanForConnectedDevices()
    }

    fun stop() {
        AppLog.i("UsbManager", "Stopping UsbCameraManager...")
        currentSessionId++
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        closeCamera(reason = "manager stopped")
        transitionTo(UsbConnectionState.DISCONNECTED)
    }

    /**
     * Updates the preview SurfaceTexture.
     * Manages a single persistent Surface and explicitly unbinds native ANativeWindow on null/change.
     */
    @Synchronized
    fun setPreviewSurface(st: SurfaceTexture?) {
        this.activeSurfaceTexture = st
        if (st != null) {
            surfaceStatus = "SurfaceTexture Available (${st.hashCode()})"
            // Release previous surface before wrapping new SurfaceTexture
            activeSurface?.release()
            activeSurface = Surface(st)
            AppLog.i("UsbManager", "setPreviewSurface: Surface created ($surfaceStatus)")

            // If camera is already open, attach this new surface to native UVCCamera
            uvcCamera?.let { cam ->
                try {
                    cam.setPreviewDisplay(activeSurface)
                    AppLog.i("UsbManager", "setPreviewDisplay updated with active Surface")
                    if (state == UsbConnectionState.FRAMES_RECEIVED) {
                        transitionTo(UsbConnectionState.PREVIEW_RENDERING)
                    }
                } catch (e: Exception) {
                    AppLog.e("UsbManager", "Failed to update preview display: ${e.message}")
                }
            }

            // If we already had permission and were waiting for surface, start stream now
            if (state == UsbConnectionState.USB_PERMISSION_GRANTED) {
                activeDevice?.let { dev ->
                    startStreamInternal(dev, currentSessionId)
                }
            }
        } else {
            surfaceStatus = "Surface Destroyed"
            AppLog.i("UsbManager", "setPreviewSurface: destroyed")
            try {
                // Inform native UVCCamera to release its ANativeWindow immediately
                uvcCamera?.setPreviewDisplay(null as Surface?)
            } catch (_: Exception) {}
            activeSurface?.release()
            activeSurface = null
        }
        notifyDiagnostics()
    }

    /**
     * Handles attached device intent from MainActivity onNewIntent.
     */
    fun onNewIntentAttachedDevice(device: UsbDevice?) {
        if (device != null) {
            handleDeviceAttached(device, source = "NEW_INTENT")
        } else {
            scanForConnectedDevices()
        }
    }

    /**
     * Checks if permission was granted while the Activity was paused (e.g. returning from OS dialog).
     */
    fun onResumeCheck() {
        val dev = activeDevice ?: run {
            if (state == UsbConnectionState.DISCONNECTED) scanForConnectedDevices()
            return
        }
        AppLog.d("UsbManager", "onResumeCheck: state=$state, dev=${dev.deviceName}")
        if (state == UsbConnectionState.PERMISSION_PENDING || state == UsbConnectionState.USB_DETECTED) {
            if (usbManager.hasPermission(dev)) {
                AppLog.i("UsbManager", "Permission granted verified in onResumeCheck for ${dev.deviceName}")
                transitionTo(UsbConnectionState.USB_PERMISSION_GRANTED)
                startStreamInternal(dev, currentSessionId)
            }
        }
    }

    fun scanForConnectedDevices() {
        val deviceList = usbManager.deviceList
        AppLog.d("UsbManager", "Scanning deviceList (${deviceList.size} devices present)")
        for (device in deviceList.values) {
            if (isUvcSupported(device)) {
                handleDeviceAttached(device, source = "SCAN")
                return
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice, source: String) {
        val vid = String.format("%04X", device.vendorId)
        val pid = String.format("%04X", device.productId)

        val ifaces = StringBuilder()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val desc = when (iface.interfaceClass) {
                USB_CLASS_VIDEO -> "Video/UVC"
                1 -> "Audio/UAC"
                USB_CLASS_PTP -> "StillImage/PTP (Preserved - Not Claimed)"
                8 -> "MassStorage"
                else -> "Class ${iface.interfaceClass}"
            }
            ifaces.append("\n  • Iface #$i: $desc (subclass=${iface.interfaceSubclass}, proto=${iface.interfaceProtocol}, endpoints=${iface.endpointCount})")
        }
        AppLog.i("UsbManager", "[$source] Device attached: ${device.deviceName} (VID=$vid, PID=$pid, ifaces=${device.interfaceCount})$ifaces")

        // 1. Verify UVC support and preserve PTP
        if (!isUvcSupported(device)) {
            AppLog.w("UsbManager", "Device ${device.deviceName} does not expose UVC (Class 14). Preserving PTP or other mode.")
            return
        }

        // 2. Prevent duplicate initialization if this exact device is already streaming or opening
        if (activeDevice?.deviceId == device.deviceId &&
            (state == UsbConnectionState.PREVIEW_STARTED ||
             state == UsbConnectionState.FRAMES_RECEIVED ||
             state == UsbConnectionState.PREVIEW_RENDERING ||
             state == UsbConnectionState.USB_DEVICE_OPENED ||
             state == UsbConnectionState.UVC_INITIALIZED)) {
            AppLog.i("UsbManager", "Ignoring duplicate attach event: session already active in state $state")
            return
        }

        // Generate new session token
        val sessionId = ++currentSessionId
        activeDevice = device
        transitionTo(UsbConnectionState.USB_DETECTED)

        // 3. Asynchronously request permission if not already granted
        if (usbManager.hasPermission(device)) {
            AppLog.i("UsbManager", "Permission already granted by OS for ${device.deviceName}")
            transitionTo(UsbConnectionState.USB_PERMISSION_GRANTED)
            startStreamInternal(device, sessionId)
        } else {
            requestUsbPermission(device, sessionId)
        }
    }

    private fun requestUsbPermission(device: UsbDevice, sessionId: Long) {
        transitionTo(UsbConnectionState.PERMISSION_PENDING)
        AppLog.i("UsbManager", "Requesting OS USB permission for ${device.deviceName} (session=$sessionId)...")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
            putExtra("EXTRA_SESSION_ID", sessionId)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, sessionId.toInt(), intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handlePermissionResult(intent: Intent, device: UsbDevice?) {
        val targetDevice = device ?: activeDevice
        val intentSessionId = intent.getLongExtra("EXTRA_SESSION_ID", currentSessionId)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        AppLog.i("UsbManager", "Permission callback: granted=$granted, dev=${targetDevice?.deviceName}, session=$intentSessionId (curr=$currentSessionId)")

        // Validate session ID
        if (intentSessionId != currentSessionId) {
            AppLog.w("UsbManager", "Ignoring stale permission callback from session $intentSessionId (current: $currentSessionId)")
            return
        }

        if (targetDevice == null) {
            AppLog.e("UsbManager", "Permission result received with null device reference!")
            lastError = "Permission callback had null device"
            transitionTo(UsbConnectionState.ERROR)
            return
        }

        if (granted) {
            activeDevice = targetDevice
            transitionTo(UsbConnectionState.USB_PERMISSION_GRANTED)
            startStreamInternal(targetDevice, currentSessionId)
        } else {
            AppLog.w("UsbManager", "USB permission denied by user for ${targetDevice.deviceName}")
            lastError = "Permission denied by user"
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        AppLog.w("UsbManager", "Device detached: ${device.deviceName} (active=${activeDevice?.deviceName})")
        if (activeDevice?.deviceId == device.deviceId) {
            currentSessionId++
            closeCamera(reason = "device physically disconnected")
            activeDevice = null
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    @Synchronized
    private fun startStreamInternal(device: UsbDevice, sessionId: Long) {
        if (sessionId != currentSessionId) {
            AppLog.w("UsbManager", "startStreamInternal aborted: session $sessionId is stale (curr: $currentSessionId)")
            return
        }

        if (state == UsbConnectionState.PREVIEW_STARTED ||
            state == UsbConnectionState.FRAMES_RECEIVED ||
            state == UsbConnectionState.PREVIEW_RENDERING) {
            AppLog.i("UsbManager", "Stream already running ($state), skipping duplicate start")
            return
        }

        AppLog.i("UsbManager", "Starting UVC stream pipeline for ${device.deviceName} (session=$sessionId)...")

        Thread {
            try {
                // 1. Open USB device connection
                val ctrlBlock = dummyMonitor.openDevice(device)
                if (ctrlBlock == null) {
                    AppLog.e("UsbManager", "Failed to open UsbControlBlock for ${device.deviceName}")
                    lastError = "openDevice failed (UsbControlBlock is null)"
                    mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    return@Thread
                }
                activeCtrlBlock = ctrlBlock
                val fd = ctrlBlock.fileDescriptor
                AppLog.i("UsbManager", "USB_DEVICE_OPENED: fd=$fd, bus=${ctrlBlock.busNum}, dev=${ctrlBlock.devNum}")
                mainHandler.post { transitionTo(UsbConnectionState.USB_DEVICE_OPENED) }

                // 2. Instantiate and open UVCCamera
                val camera = UVCCamera()
                camera.open(ctrlBlock)
                uvcCamera = camera
                AppLog.i("UsbManager", "UVC_INITIALIZED: native UVCCamera.open() success")
                mainHandler.post { transitionTo(UsbConnectionState.UVC_INITIALIZED) }

                // 3. Log camera advertised UVC capabilities
                try {
                    val supportedJson = camera.supportedSize
                    AppLog.i("UsbManager", "Advertised formats JSON: $supportedJson")
                    val sizes = camera.supportedSizeList
                    AppLog.i("UsbManager", "Supported sizes: ${sizes?.joinToString { "${it.width}x${it.height}" }}")
                } catch (e: Exception) {
                    AppLog.w("UsbManager", "Could not query supported sizes: ${e.message}")
                }

                // 4. Configure 1280x720 MJPEG
                camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                resolutionFormat = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG"
                AppLog.i("UsbManager", "Configured UVC preview format: $resolutionFormat")

                // 5. Register native frame callback for real frame delivery diagnostics
                totalFrames = 0L
                framesThisSecond = 0
                lastFpsCalcTime = System.currentTimeMillis()

                camera.setFrameCallback({ byteBuffer ->
                    onNativeFrameReceived(byteBuffer, sessionId)
                }, UVCCamera.PIXEL_FORMAT_RGBX)
                AppLog.i("UsbManager", "Native frame callback registered (PIXEL_FORMAT_RGBX)")

                // 6. Connect preview Surface to native ANativeWindow if ready
                val surface = activeSurface
                if (surface != null && surface.isValid) {
                    camera.setPreviewDisplay(surface)
                    AppLog.i("UsbManager", "Preview surface attached to UVCCamera")
                } else {
                    AppLog.i("UsbManager", "SurfaceTexture not ready yet; will attach upon availability")
                }

                // 7. Start native UVC stream
                camera.startPreview()
                AppLog.i("UsbManager", "PREVIEW_STARTED: camera.startPreview() returned OK")
                mainHandler.post { transitionTo(UsbConnectionState.PREVIEW_STARTED) }

            } catch (e: Exception) {
                AppLog.e("UsbManager", "Exception during UVC stream startup: ${e.message}", e)
                lastError = e.message ?: "Startup exception"
                closeCamera(reason = "startup exception")
                mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
            }
        }.start()
    }

    private fun onNativeFrameReceived(frame: ByteBuffer, sessionId: Long) {
        if (sessionId != currentSessionId) return

        val size = frame.remaining()
        totalFrames++
        lastFrameTimeMs = System.currentTimeMillis()
        lastFrameSizeBytes = size
        framesThisSecond++

        val now = System.currentTimeMillis()
        if (now - lastFpsCalcTime >= 1000) {
            currentFps = framesThisSecond
            framesThisSecond = 0
            lastFpsCalcTime = now
            AppLog.i("UVCStream", "UVC Live: $currentFps fps | Total: $totalFrames frames | Last frame: $lastFrameSizeBytes bytes")
            notifyDiagnostics()
        }

        // Transition from PREVIEW_STARTED → FRAMES_RECEIVED when first frame is confirmed
        if (state == UsbConnectionState.PREVIEW_STARTED) {
            mainHandler.post {
                if (state == UsbConnectionState.PREVIEW_STARTED) {
                    AppLog.i("UsbManager", "First real video frame confirmed! (size=$size bytes)")
                    transitionTo(UsbConnectionState.FRAMES_RECEIVED)
                }
            }
        }

        // Transition from FRAMES_RECEIVED → PREVIEW_RENDERING when surface is attached
        if (state == UsbConnectionState.FRAMES_RECEIVED && activeSurface?.isValid == true) {
            mainHandler.post {
                if (state == UsbConnectionState.FRAMES_RECEIVED) {
                    AppLog.i("UsbManager", "PREVIEW_RENDERING: Frames actively rendering onto screen Surface")
                    transitionTo(UsbConnectionState.PREVIEW_RENDERING)
                }
            }
        }
    }

    @Synchronized
    private fun closeCamera(reason: String) {
        AppLog.i("UsbManager", "closeCamera() reason: $reason [uvcCamera=${uvcCamera != null}, ctrlBlock=${activeCtrlBlock != null}]")
        try {
            // Unbind native display surface before destroying
            uvcCamera?.setPreviewDisplay(null as Surface?)
            uvcCamera?.setFrameCallback(null, 0)
            uvcCamera?.stopPreview()
            uvcCamera?.destroy()
        } catch (e: Exception) {
            AppLog.w("UsbManager", "Error stopping UVCCamera: ${e.message}")
        } finally {
            uvcCamera = null
        }

        try {
            activeCtrlBlock?.close()
        } catch (e: Exception) {
            AppLog.w("UsbManager", "Error closing UsbControlBlock: ${e.message}")
        } finally {
            activeCtrlBlock = null
        }

        currentFps = 0
        notifyDiagnostics()
    }

    private fun isUvcSupported(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun transitionTo(newState: UsbConnectionState) {
        val oldState = state
        if (oldState == newState) return
        state = newState
        AppLog.i("State", "STATE: $oldState → $newState")
        notifyDiagnostics()
        stateListener(oldState, newState)
    }

    private fun notifyDiagnostics() {
        val dev = activeDevice
        val vid = dev?.let { String.format("%04X", it.vendorId) } ?: "----"
        val pid = dev?.let { String.format("%04X", it.productId) } ?: "----"
        val hasPerm = dev?.let { usbManager.hasPermission(it) } ?: false

        val info = DiagnosticInfo(
            state = state,
            deviceName = dev?.deviceName ?: "None",
            vidPid = "$vid:$pid",
            permissionGranted = hasPerm,
            resolutionFormat = resolutionFormat,
            totalFrames = totalFrames,
            fps = currentFps,
            lastFrameTimeMs = lastFrameTimeMs,
            lastFrameSizeBytes = lastFrameSizeBytes,
            surfaceStatus = surfaceStatus,
            lastError = lastError
        )
        mainHandler.post {
            diagnosticsListener?.invoke(info)
        }
    }

    fun getDiagnosticInfo(): DiagnosticInfo {
        val dev = activeDevice
        val vid = dev?.let { String.format("%04X", it.vendorId) } ?: "----"
        val pid = dev?.let { String.format("%04X", it.productId) } ?: "----"
        val hasPerm = dev?.let { usbManager.hasPermission(it) } ?: false

        return DiagnosticInfo(
            state = state,
            deviceName = dev?.deviceName ?: "None",
            vidPid = "$vid:$pid",
            permissionGranted = hasPerm,
            resolutionFormat = resolutionFormat,
            totalFrames = totalFrames,
            fps = currentFps,
            lastFrameTimeMs = lastFrameTimeMs,
            lastFrameSizeBytes = lastFrameSizeBytes,
            surfaceStatus = surfaceStatus,
            lastError = lastError
        )
    }
}
