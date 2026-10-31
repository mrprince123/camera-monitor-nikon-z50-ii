package com.example.cameramonitor.camera.usb.uvc

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.cameramonitor.AppLog
import com.example.cameramonitor.camera.CameraBackend
import com.example.cameramonitor.camera.CameraCapabilities
import com.example.cameramonitor.camera.CameraDevice
import com.example.cameramonitor.camera.CameraState
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * UVC Camera Backend for USB-C Webcam streaming and HDMI capture dongles.
 * Encapsulates native UVCCamera lifecycle with zero leaks and robust -99 error handling.
 */
class UvcCameraBackend(
    private val context: Context,
    override val backendType: CameraDevice.ConnectionType = CameraDevice.ConnectionType.USB_UVC
) : CameraBackend {

    companion object {
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
        const val MAX_OPEN_ATTEMPTS = 3
        const val RETRY_SETTLING_DELAY_MS = 350L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    override val capabilities: CameraCapabilities = when (backendType) {
        CameraDevice.ConnectionType.HDMI_CAPTURE -> CameraCapabilities.HDMI_CAPTURE
        else -> CameraCapabilities.UVC_DEFAULT
    }

    @Volatile
    private var activeSessionId: Long = 0L

    @Volatile
    private var uvcCamera: UVCCamera? = null

    @Volatile
    private var activeCtrlBlock: USBMonitor.UsbControlBlock? = null

    @Volatile
    private var activeSurfaceTexture: SurfaceTexture? = null

    // Frame metrics
    @Volatile var totalFrames: Long = 0L; private set
    @Volatile var currentFps: Int = 0; private set
    @Volatile var lastFrameSizeBytes: Int = 0; private set
    @Volatile var lastFrameTimeMs: Long = 0L; private set
    private var framesThisSecond = 0
    private var lastFpsCalcTime = 0L

    override val diagnosticSummary: String
        get() = "UVC [$backendType] | Frames: $totalFrames ($currentFps fps) | Size: ${lastFrameSizeBytes}B | Res: ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG"

    // Dummy USBMonitor instance used as a factory for UsbControlBlock
    private val dummyMonitor: USBMonitor = USBMonitor.getInstance(context).apply {
        setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {}
            override fun onDetach(device: UsbDevice?) {}
            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
            override fun onCancel(device: UsbDevice?) {}
        })
    }

    override suspend fun connect(device: UsbDevice, sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        AppLog.i("UvcBackend", "[Session #$sessionId] Connecting to ${device.deviceName} via UVC backend...")
        activeSessionId = sessionId
        _state.value = CameraState.Connecting("Opening USB device...")

        // Step 1: Open UsbControlBlock
        val ctrlBlock = dummyMonitor.openDevice(device)
        if (ctrlBlock == null) {
            val err = "openDevice failed: UsbControlBlock is null (no connection or permission revoked)"
            AppLog.e("UvcBackend", "[Session #$sessionId] $err")
            _state.value = CameraState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        if (sessionId != activeSessionId) {
            AppLog.w("UvcBackend", "[Session #$sessionId] Aborted: session superseded")
            try { ctrlBlock.close() } catch (_: Exception) {}
            return@withContext Result.failure(IllegalStateException("Session superseded"))
        }

        activeCtrlBlock = ctrlBlock
        _state.value = CameraState.Connecting("Initializing native UVCCamera...")

        // Step 2: Open native UVCCamera with bounded retry
        var camera: UVCCamera? = null
        var openSuccess = false
        var openAttempt = 0

        while (!openSuccess && openAttempt < MAX_OPEN_ATTEMPTS) {
            openAttempt++
            if (sessionId != activeSessionId) {
                try { ctrlBlock.close() } catch (_: Exception) {}
                return@withContext Result.failure(IllegalStateException("Session superseded"))
            }

            val candidateCam = UVCCamera()
            try {
                AppLog.i("UvcBackend", "[Session #$sessionId] Calling native UVCCamera.open() (attempt #$openAttempt/$MAX_OPEN_ATTEMPTS)...")
                candidateCam.open(ctrlBlock)
                camera = candidateCam
                openSuccess = true
                AppLog.i("UvcBackend", "[Session #$sessionId] UVCCamera.open() SUCCESS on attempt #$openAttempt")
            } catch (e: Exception) {
                // Must destroy candidate to prevent descriptor leaks
                try { candidateCam.destroy() } catch (_: Exception) {}
                val msg = e.message ?: e.javaClass.simpleName
                AppLog.w("UvcBackend", "[Session #$sessionId] UVCCamera.open() attempt #$openAttempt failed: $msg")

                if (openAttempt < MAX_OPEN_ATTEMPTS && sessionId == activeSessionId) {
                    AppLog.i("UvcBackend", "[Session #$sessionId] Waiting ${RETRY_SETTLING_DELAY_MS}ms for USB settling...")
                    try { Thread.sleep(RETRY_SETTLING_DELAY_MS) } catch (_: InterruptedException) {}
                } else {
                    val err = "UVCCamera.open() failed after $openAttempt attempts: $msg"
                    AppLog.e("UvcBackend", "[Session #$sessionId] $err", e)
                    cleanupNativeResources("open failed")
                    _state.value = CameraState.Error(err)
                    return@withContext Result.failure(e)
                }
            }
        }

        if (camera == null || !openSuccess) {
            cleanupNativeResources("camera null")
            return@withContext Result.failure(IllegalStateException("Camera initialization failed"))
        }

        uvcCamera = camera

        // Step 3: Query Advertised Capabilities
        try {
            val supportedJson = camera.getSupportedSize()
            AppLog.i("UvcBackend", "[Session #$sessionId] Advertised formats JSON: $supportedJson")
        } catch (e: Exception) {
            AppLog.w("UvcBackend", "[Session #$sessionId] Could not query supported formats: ${e.message}")
        }

        // Step 4: Configure Preview Size
        try {
            AppLog.i("UvcBackend", "[Session #$sessionId] Setting preview size to ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG...")
            camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
        } catch (e: Exception) {
            val err = "setPreviewSize failed: ${e.message}"
            AppLog.e("UvcBackend", "[Session #$sessionId] $err", e)
            cleanupNativeResources(err)
            _state.value = CameraState.Error(err)
            return@withContext Result.failure(e)
        }

        // Step 5: Register Frame Callback
        totalFrames = 0L
        framesThisSecond = 0
        lastFpsCalcTime = System.currentTimeMillis()

        try {
            camera.setFrameCallback({ byteBuffer ->
                onNativeFrameReceived(byteBuffer, sessionId)
            }, UVCCamera.PIXEL_FORMAT_RAW)
            AppLog.i("UvcBackend", "[Session #$sessionId] Native frame callback registered")
        } catch (e: Exception) {
            val err = "setFrameCallback failed: ${e.message}"
            AppLog.e("UvcBackend", "[Session #$sessionId] $err", e)
            cleanupNativeResources(err)
            _state.value = CameraState.Error(err)
            return@withContext Result.failure(e)
        }

        val cameraDev = CameraDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName ?: "UVC Camera",
            connectionType = backendType
        )
        _state.value = CameraState.Connected(cameraDev, capabilities)

        // Step 6 & 7: Attach SurfaceTexture and Start Preview if surface is already available
        val st = activeSurfaceTexture
        if (st != null) {
            try {
                camera.setPreviewTexture(st)
                camera.startPreview()
                AppLog.i("UvcBackend", "[Session #$sessionId] Preview started with attached SurfaceTexture")
            } catch (e: Exception) {
                val err = "startPreview failed: ${e.message}"
                AppLog.e("UvcBackend", "[Session #$sessionId] $err", e)
                cleanupNativeResources(err)
                _state.value = CameraState.Error(err)
                return@withContext Result.failure(e)
            }
        } else {
            AppLog.i("UvcBackend", "[Session #$sessionId] SurfaceTexture not yet ready; deferring startPreview")
        }

        Result.success(Unit)
    }

    override suspend fun disconnect(reason: String) = withContext(Dispatchers.IO) {
        AppLog.i("UvcBackend", "Disconnecting UvcCameraBackend (reason: $reason)...")
        activeSessionId++
        cleanupNativeResources(reason)
        _state.value = CameraState.Disconnected
    }

    @Synchronized
    override fun setPreviewSurface(surfaceTexture: SurfaceTexture?) {
        this.activeSurfaceTexture = surfaceTexture
        val cam = uvcCamera

        if (surfaceTexture != null) {
            AppLog.i("UvcBackend", "setPreviewSurface: SurfaceTexture attached (${surfaceTexture.hashCode()})")
            if (cam != null) {
                scope.launch {
                    try {
                        cam.stopPreview()
                        cam.setPreviewTexture(surfaceTexture)
                        cam.startPreview()
                        AppLog.i("UvcBackend", "Preview restarted with new SurfaceTexture")
                    } catch (e: Exception) {
                        AppLog.e("UvcBackend", "Error reattaching SurfaceTexture: ${e.message}", e)
                    }
                }
            }
        } else {
            AppLog.i("UvcBackend", "setPreviewSurface: SurfaceTexture destroyed")
            if (cam != null) {
                try {
                    cam.stopPreview()
                    cam.setPreviewDisplay(null as Surface?)
                } catch (_: Exception) {}
            }
        }
    }

    private fun onNativeFrameReceived(frame: ByteBuffer, sessionId: Long) {
        if (sessionId != activeSessionId) return

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
            AppLog.d("UvcBackend", "[Session #$sessionId] Live: $currentFps fps | Total: $totalFrames frames | Size: ${lastFrameSizeBytes}B")
        }

        // Update state to Streaming once video frames are actively flowing
        val currentState = _state.value
        if (currentState !is CameraState.Streaming) {
            mainHandler.post {
                if (activeSessionId == sessionId) {
                    _state.value = CameraState.Streaming(
                        fps = currentFps,
                        totalFrames = totalFrames,
                        resolution = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT}",
                        isRendering = (activeSurfaceTexture != null)
                    )
                }
            }
        } else if (now - lastFpsCalcTime < 100) {
            // Periodic update of frame count and fps
            _state.value = CameraState.Streaming(
                fps = currentFps,
                totalFrames = totalFrames,
                resolution = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT}",
                isRendering = (activeSurfaceTexture != null)
            )
        }
    }

    @Synchronized
    private fun cleanupNativeResources(reason: String) {
        AppLog.i("UvcBackend", "cleanupNativeResources() reason: $reason")

        val cam = uvcCamera
        uvcCamera = null
        if (cam != null) {
            try { cam.setPreviewDisplay(null as Surface?) } catch (_: Exception) {}
            try { cam.setFrameCallback(null, 0) } catch (_: Exception) {}
            try { cam.stopPreview() } catch (_: Exception) {}
            try { cam.destroy() } catch (e: Exception) {
                AppLog.w("UvcBackend", "Error destroying UVCCamera: ${e.message}")
            }
        }

        val ctrl = activeCtrlBlock
        activeCtrlBlock = null
        if (ctrl != null) {
            try { ctrl.close() } catch (e: Exception) {
                AppLog.w("UvcBackend", "Error closing UsbControlBlock: ${e.message}")
            }
        }

        currentFps = 0
    }
}

