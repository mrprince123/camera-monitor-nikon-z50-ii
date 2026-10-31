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
import java.util.concurrent.Executors

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
 *  1. Strict single-owner deduplication: ignores duplicate attach events from BroadcastReceiver
 *     and onNewIntent while a connection is already pending or active.
 *  2. Serialized connection executor: guarantees that only one connection thread runs at any time,
 *     eliminating race conditions and concurrent USB open collisions.
 *  3. Session token guards: every async step validates sessionId == currentSessionId; stale
 *     callbacks from older attempts cannot mutate or release a newer active session.
 *  4. Native resource safety: if UVCCamera.open() fails with result=-99, the candidate camera
 *     instance is immediately destroyed to prevent leaking native pointers or cloned UsbControlBlocks.
 *  5. Automatic settling retry: composite cameras like the Nikon Z50 II can fail with -99
 *     if opened while descriptors are still enumerating; performs a clean bounded retry
 *     with a 350ms settling delay before reporting an error.
 *  6. Exact failure recording: logs and records the exact operation that failed with its error code.
 *  7. Live frame verification: tracks actual delivered video frames and fps to confirm active streaming.
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

    // Serialized background executor to prevent concurrent USB open attempts
    private val connectionExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var state = UsbConnectionState.DISCONNECTED
        private set

    // Session token to invalidate stale/delayed callbacks
    @Volatile
    private var currentSessionId = 0L

    // Guard against concurrent connection attempts
    @Volatile
    private var isConnectionInProgress = false

    // Active connection references
    private var activeDevice: UsbDevice? = null
    private var activeCtrlBlock: USBMonitor.UsbControlBlock? = null
    private var uvcCamera: UVCCamera? = null

    // SurfaceTexture passed in by UI (attached directly without Surface wrapper)
    @Volatile
    private var activeSurfaceTexture: SurfaceTexture? = null

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

    // USBMonitor used ONLY as factory for UsbControlBlock
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
        closeCamera("manager stopped")
        transitionTo(UsbConnectionState.DISCONNECTED)
    }

    /**
     * Updates the preview SurfaceTexture.
     * Directly binds SurfaceTexture to UVCCamera.setPreviewTexture without wrapping in a Surface.
     */
    @Synchronized
    fun setPreviewSurface(st: SurfaceTexture?) {
        this.activeSurfaceTexture = st
        if (st != null) {
            surfaceStatus = "SurfaceTexture Available (${st.hashCode()})"
            AppLog.i("UsbManager", "setPreviewSurface: SurfaceTexture arrived ($surfaceStatus)")

            val cam = uvcCamera
            if (cam != null) {
                val wasStreaming = (state == UsbConnectionState.PREVIEW_STARTED ||
                                   state == UsbConnectionState.FRAMES_RECEIVED ||
                                   state == UsbConnectionState.PREVIEW_RENDERING)
                try {
                    if (wasStreaming) {
                        AppLog.i("UsbManager", "Camera already streaming — stopping to reattach SurfaceTexture")
                        cam.stopPreview()
                    }
                    cam.setPreviewTexture(st)
                    AppLog.i("UsbManager", "setPreviewTexture() called successfully on UVCCamera")
                    surfaceStatus = "SurfaceTexture Attached (${st.hashCode()})"
                    if (wasStreaming) {
                        cam.startPreview()
                        AppLog.i("UsbManager", "startPreview() called after SurfaceTexture reattach")
                    }
                } catch (e: Exception) {
                    val err = "setPreviewTexture failed: ${e.message}"
                    AppLog.e("UsbManager", err, e)
                    lastError = err
                }
            } else {
                AppLog.i("UsbManager", "No active UVCCamera yet; SurfaceTexture stored for use when camera opens")
            }

            // If we have an active device in PERMISSION_GRANTED or UVC_INITIALIZED, start/resume stream
            if (state == UsbConnectionState.USB_PERMISSION_GRANTED ||
                state == UsbConnectionState.UVC_INITIALIZED) {
                activeDevice?.let { dev ->
                    AppLog.i("UsbManager", "Surface now ready; triggering deferred startStreamInternal (session=$currentSessionId)")
                    startStreamInternal(dev, currentSessionId)
                }
            }
        } else {
            surfaceStatus = "Surface Destroyed"
            AppLog.i("UsbManager", "setPreviewSurface: SurfaceTexture destroyed; detaching from native camera")
            try {
                val cam = uvcCamera
                if (cam != null) {
                    cam.stopPreview()
                    cam.setPreviewDisplay(null as Surface?)
                    AppLog.i("UsbManager", "Preview stopped and display detached due to surface destruction")
                }
            } catch (_: Exception) {}
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
        AppLog.d("UsbManager", "onResumeCheck: state=$state, dev=${dev.deviceName}, session=$currentSessionId")
        if (state == UsbConnectionState.PERMISSION_PENDING || state == UsbConnectionState.USB_DETECTED) {
            if (usbManager.hasPermission(dev)) {
                AppLog.i("UsbManager", "[Session #$currentSessionId] Permission verified in onResumeCheck for ${dev.deviceName}")
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

        // 2. Prevent duplicate initialization if this exact device is already active or opening
        //    FIX: Checks ALL non-terminal states so concurrent BROADCAST + NEW_INTENT + SCAN events
        //    do not invalidate pending sessions or spawn colliding connection threads!
        if (activeDevice?.deviceId == device.deviceId &&
            state != UsbConnectionState.DISCONNECTED &&
            state != UsbConnectionState.ERROR) {
            AppLog.i("UsbManager", "[$source] Ignoring duplicate attach event: device ${device.deviceName} already active/pending in state $state (session=$currentSessionId)")
            return
        }

        // Generate new session token
        val sessionId = ++currentSessionId
        activeDevice = device
        transitionTo(UsbConnectionState.USB_DETECTED)
        AppLog.i("UsbManager", "[Session #$sessionId] Assigned new connection session for ${device.deviceName}")

        // 3. Request permission if not already granted
        if (usbManager.hasPermission(device)) {
            AppLog.i("UsbManager", "[Session #$sessionId] Permission already granted by OS for ${device.deviceName}")
            transitionTo(UsbConnectionState.USB_PERMISSION_GRANTED)
            startStreamInternal(device, sessionId)
        } else {
            requestUsbPermission(device, sessionId)
        }
    }

    private fun requestUsbPermission(device: UsbDevice, sessionId: Long) {
        transitionTo(UsbConnectionState.PERMISSION_PENDING)
        AppLog.i("UsbManager", "[Session #$sessionId] Requesting OS USB permission for ${device.deviceName}...")

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

        // Validate session ID: reject stale callbacks belonging to older attempts
        if (intentSessionId != currentSessionId) {
            AppLog.w("UsbManager", "Ignoring stale permission callback from session $intentSessionId (current: $currentSessionId)")
            return
        }

        if (targetDevice == null) {
            AppLog.e("UsbManager", "[Session #$currentSessionId] Permission result received with null device reference!")
            lastError = "Permission callback had null device"
            transitionTo(UsbConnectionState.ERROR)
            return
        }

        if (granted) {
            activeDevice = targetDevice
            transitionTo(UsbConnectionState.USB_PERMISSION_GRANTED)
            startStreamInternal(targetDevice, currentSessionId)
        } else {
            AppLog.w("UsbManager", "[Session #$currentSessionId] USB permission denied by user for ${targetDevice.deviceName}")
            lastError = "Permission denied by user"
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        AppLog.w("UsbManager", "Device detached: ${device.deviceName} (active=${activeDevice?.deviceName}, session=$currentSessionId)")
        if (activeDevice?.deviceId == device.deviceId) {
            currentSessionId++
            closeCamera(reason = "device physically disconnected")
            activeDevice = null
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    /**
     * Initiates the UVC camera pipeline.
     * Enforces single-thread execution and guards against concurrent open collisions.
     */
    @Synchronized
    private fun startStreamInternal(device: UsbDevice, sessionId: Long) {
        if (sessionId != currentSessionId) {
            AppLog.w("UsbManager", "[Session #$sessionId] startStreamInternal aborted: session is stale (curr: $currentSessionId)")
            return
        }

        if (isConnectionInProgress) {
            AppLog.i("UsbManager", "[Session #$sessionId] Connection attempt already in progress; skipping duplicate trigger")
            return
        }

        if (state == UsbConnectionState.PREVIEW_STARTED ||
            state == UsbConnectionState.FRAMES_RECEIVED ||
            state == UsbConnectionState.PREVIEW_RENDERING) {
            AppLog.i("UsbManager", "[Session #$sessionId] Stream already running ($state); skipping duplicate start")
            return
        }

        // Fast path: if camera is already initialized (UVC_INITIALIZED) and only surface was missing,
        // attach surface and start preview without reopening USB
        val existingCamera = uvcCamera
        if (state == UsbConnectionState.UVC_INITIALIZED && existingCamera != null) {
            val st = activeSurfaceTexture
            if (st != null) {
                AppLog.i("UsbManager", "[Session #$sessionId] Surface now available for initialized camera — attaching and starting preview")
                try {
                    existingCamera.setPreviewTexture(st)
                    surfaceStatus = "SurfaceTexture Attached (${st.hashCode()})"
                    existingCamera.startPreview()
                    AppLog.i("UsbManager", "[Session #$sessionId] PREVIEW_STARTED (deferred): camera.startPreview() returned OK")
                    mainHandler.post { transitionTo(UsbConnectionState.PREVIEW_STARTED) }
                } catch (e: Exception) {
                    val err = "Deferred startPreview failed: ${e.message}"
                    AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $err", e)
                    if (sessionId == currentSessionId) {
                        lastError = err
                        closeCamera("deferred startPreview exception")
                        mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    }
                }
            } else {
                AppLog.i("UsbManager", "[Session #$sessionId] UVC_INITIALIZED but SurfaceTexture still not ready; waiting...")
            }
            return
        }

        AppLog.i("UsbManager", "[Session #$sessionId] Starting serialized UVC stream pipeline for ${device.deviceName}...")
        isConnectionInProgress = true

        connectionExecutor.execute {
            try {
                // Step 0: Ensure session is still valid
                if (sessionId != currentSessionId) {
                    AppLog.w("UsbManager", "[Session #$sessionId] Aborting before open: session is stale (curr: $currentSessionId)")
                    return@execute
                }

                // Clean up any stale previous camera / control block before opening a new one
                closeCameraInternal("cleanup before new open in session #$sessionId")

                // Step 1: Open USB device connection
                AppLog.i("UsbManager", "[Session #$sessionId] Step 1/7: Opening UsbControlBlock for ${device.deviceName}...")
                val ctrlBlock = dummyMonitor.openDevice(device)
                if (ctrlBlock == null) {
                    val opErr = "openDevice failed: UsbControlBlock is null (no connection or permission revoked)"
                    AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr")
                    if (sessionId == currentSessionId) {
                        lastError = opErr
                        mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    }
                    return@execute
                }

                if (sessionId != currentSessionId) {
                    AppLog.w("UsbManager", "[Session #$sessionId] Session became stale after openDevice; releasing ctrlBlock")
                    try { ctrlBlock.close() } catch (_: Exception) {}
                    return@execute
                }

                activeCtrlBlock = ctrlBlock
                val fd = try { ctrlBlock.fileDescriptor } catch (_: Exception) { -1 }
                AppLog.i("UsbManager", "[Session #$sessionId] Step 1/7 SUCCESS: USB_DEVICE_OPENED (fd=$fd, bus=${ctrlBlock.busNum}, dev=${ctrlBlock.devNum})")
                if (sessionId == currentSessionId) {
                    mainHandler.post { transitionTo(UsbConnectionState.USB_DEVICE_OPENED) }
                }

                // Step 2: Instantiate and open UVCCamera with safe retry on -99
                AppLog.i("UsbManager", "[Session #$sessionId] Step 2/7: Initializing native UVCCamera...")
                var camera: UVCCamera? = null
                var openSuccess = false
                val maxOpenAttempts = 3
                var openAttempt = 0

                while (!openSuccess && openAttempt < maxOpenAttempts) {
                    openAttempt++
                    if (sessionId != currentSessionId) {
                        AppLog.w("UsbManager", "[Session #$sessionId] Session became stale before open attempt #$openAttempt")
                        try { ctrlBlock.close() } catch (_: Exception) {}
                        return@execute
                    }

                    val candidateCam = UVCCamera()
                    try {
                        AppLog.i("UsbManager", "[Session #$sessionId] Step 2/7: Calling native UVCCamera.open() (attempt #$openAttempt/$maxOpenAttempts)...")
                        candidateCam.open(ctrlBlock)
                        camera = candidateCam
                        openSuccess = true
                        AppLog.i("UsbManager", "[Session #$sessionId] Step 2/7 SUCCESS: native UVCCamera.open() succeeded on attempt #$openAttempt")
                    } catch (e: Exception) {
                        // CRITICAL FIX: Explicitly destroy the candidate camera to free native C++ memory
                        // and close the cloned UsbControlBlock! Otherwise it permanently leaks open descriptors!
                        try { candidateCam.destroy() } catch (_: Exception) {}

                        val msg = e.message ?: e.javaClass.simpleName
                        AppLog.w("UsbManager", "[Session #$sessionId] UVCCamera.open() attempt #$openAttempt failed: $msg")

                        if (openAttempt < maxOpenAttempts && sessionId == currentSessionId) {
                            AppLog.i("UsbManager", "[Session #$sessionId] Waiting 350ms for USB interface settling before retry #$openAttempt...")
                            try { Thread.sleep(350) } catch (_: InterruptedException) {}
                        } else {
                            val opErr = "UVCCamera.open() failed: $msg"
                            AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr", e)
                            if (sessionId == currentSessionId) {
                                lastError = opErr
                                closeCameraInternal("open failed after $openAttempt attempts")
                                mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                            }
                            return@execute
                        }
                    }
                }

                if (camera == null || !openSuccess) return@execute
                uvcCamera = camera
                if (sessionId == currentSessionId) {
                    mainHandler.post { transitionTo(UsbConnectionState.UVC_INITIALIZED) }
                }

                // Step 3: Query Advertised Capabilities
                AppLog.i("UsbManager", "[Session #$sessionId] Step 3/7: Querying advertised UVC capabilities...")
                try {
                    val supportedJson = camera.getSupportedSize()
                    AppLog.i("UsbManager", "[Session #$sessionId] Advertised formats JSON: $supportedJson")

                    val mjpegSizes = camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)
                    AppLog.i("UsbManager", "[Session #$sessionId] Supported MJPEG sizes: ${mjpegSizes?.joinToString { "${it.width}x${it.height}" } ?: "none"}")

                    val yuyvSizes = camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)
                    AppLog.i("UsbManager", "[Session #$sessionId] Supported YUYV sizes: ${yuyvSizes?.joinToString { "${it.width}x${it.height}" } ?: "none"}")

                    val hasMjpeg1280 = mjpegSizes?.any { it.width == PREVIEW_WIDTH && it.height == PREVIEW_HEIGHT } ?: false
                    AppLog.i("UsbManager", "[Session #$sessionId] 1280x720 MJPEG supported: $hasMjpeg1280")
                } catch (e: Exception) {
                    AppLog.w("UsbManager", "[Session #$sessionId] Could not query supported sizes: ${e.message}")
                }

                // Step 4: Configure Preview Size
                AppLog.i("UsbManager", "[Session #$sessionId] Step 4/7: Setting preview size to ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG...")
                try {
                    camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                    resolutionFormat = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG"
                    AppLog.i("UsbManager", "[Session #$sessionId] Step 4/7 SUCCESS: Preview format configured as $resolutionFormat")
                } catch (e: Exception) {
                    val opErr = "setPreviewSize failed: ${e.message}"
                    AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr", e)
                    if (sessionId == currentSessionId) {
                        lastError = opErr
                        closeCameraInternal("setPreviewSize exception")
                        mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    }
                    return@execute
                }

                // Step 5: Register Native Frame Callback
                AppLog.i("UsbManager", "[Session #$sessionId] Step 5/7: Registering native frame callback (PIXEL_FORMAT_RAW)...")
                totalFrames = 0L
                framesThisSecond = 0
                lastFpsCalcTime = System.currentTimeMillis()

                try {
                    camera.setFrameCallback({ byteBuffer ->
                        onNativeFrameReceived(byteBuffer, sessionId)
                    }, UVCCamera.PIXEL_FORMAT_RAW)
                    AppLog.i("UsbManager", "[Session #$sessionId] Step 5/7 SUCCESS: Native frame callback registered")
                } catch (e: Exception) {
                    val opErr = "setFrameCallback failed: ${e.message}"
                    AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr", e)
                    if (sessionId == currentSessionId) {
                        lastError = opErr
                        closeCameraInternal("setFrameCallback exception")
                        mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    }
                    return@execute
                }

                // Step 6: Attach SurfaceTexture
                AppLog.i("UsbManager", "[Session #$sessionId] Step 6/7: Checking preview surface...")
                val st = activeSurfaceTexture
                if (st != null) {
                    try {
                        camera.setPreviewTexture(st)
                        surfaceStatus = "SurfaceTexture Attached (${st.hashCode()})"
                        AppLog.i("UsbManager", "[Session #$sessionId] Step 6/7 SUCCESS: setPreviewTexture attached")
                    } catch (e: Exception) {
                        val opErr = "setPreviewTexture failed: ${e.message}"
                        AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr", e)
                        if (sessionId == currentSessionId) {
                            lastError = opErr
                            closeCameraInternal("setPreviewTexture exception")
                            mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                        }
                        return@execute
                    }
                } else {
                    AppLog.w("UsbManager", "[Session #$sessionId] SurfaceTexture not ready; deferring startPreview until surface arrives")
                    if (sessionId == currentSessionId) {
                        mainHandler.post { transitionTo(UsbConnectionState.UVC_INITIALIZED) }
                    }
                    return@execute
                }

                // Step 7: Start Preview
                AppLog.i("UsbManager", "[Session #$sessionId] Step 7/7: Starting native preview...")
                try {
                    camera.startPreview()
                    AppLog.i("UsbManager", "[Session #$sessionId] Step 7/7 SUCCESS: PREVIEW_STARTED returned OK")
                    if (sessionId == currentSessionId) {
                        mainHandler.post { transitionTo(UsbConnectionState.PREVIEW_STARTED) }
                    }
                } catch (e: Exception) {
                    val opErr = "startPreview failed: ${e.message}"
                    AppLog.e("UsbManager", "[Session #$sessionId] OPERATION FAILED: $opErr", e)
                    if (sessionId == currentSessionId) {
                        lastError = opErr
                        closeCameraInternal("startPreview exception")
                        mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    }
                }

            } finally {
                isConnectionInProgress = false
            }
        }
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
            AppLog.i("UVCStream", "[Session #$sessionId] Live: $currentFps fps | Total: $totalFrames frames | Last frame: $lastFrameSizeBytes bytes")
            notifyDiagnostics()
        }

        // Transition from PREVIEW_STARTED → FRAMES_RECEIVED when first frame arrives
        if (state == UsbConnectionState.PREVIEW_STARTED) {
            mainHandler.post {
                if (state == UsbConnectionState.PREVIEW_STARTED && sessionId == currentSessionId) {
                    AppLog.i("UsbManager", "[Session #$sessionId] First real video frame confirmed! (size=$size bytes)")
                    transitionTo(UsbConnectionState.FRAMES_RECEIVED)
                }
            }
        }

        // Transition from FRAMES_RECEIVED → PREVIEW_RENDERING when surface is attached
        if (state == UsbConnectionState.FRAMES_RECEIVED && activeSurfaceTexture != null) {
            mainHandler.post {
                if (state == UsbConnectionState.FRAMES_RECEIVED && sessionId == currentSessionId) {
                    AppLog.i("UsbManager", "[Session #$sessionId] PREVIEW_RENDERING: Frames actively rendering onto SurfaceTexture")
                    transitionTo(UsbConnectionState.PREVIEW_RENDERING)
                }
            }
        }
    }

    @Synchronized
    private fun closeCamera(reason: String) {
        closeCameraInternal(reason)
        currentFps = 0
        notifyDiagnostics()
    }

    /**
     * Internal cleanup: releases native ANativeWindow, frame callbacks, UVCCamera, and UsbControlBlock.
     */
    private fun closeCameraInternal(reason: String) {
        AppLog.i("UsbManager", "closeCameraInternal() reason: $reason [uvcCamera=${uvcCamera != null}, ctrlBlock=${activeCtrlBlock != null}]")

        val cam = uvcCamera
        uvcCamera = null
        if (cam != null) {
            try {
                cam.setPreviewDisplay(null as Surface?)
            } catch (_: Exception) {}
            try {
                cam.setFrameCallback(null, 0)
            } catch (_: Exception) {}
            try {
                cam.stopPreview()
            } catch (_: Exception) {}
            try {
                cam.destroy()
            } catch (e: Exception) {
                AppLog.w("UsbManager", "Error destroying UVCCamera: ${e.message}")
            }
        }

        val ctrl = activeCtrlBlock
        activeCtrlBlock = null
        if (ctrl != null) {
            try {
                ctrl.close()
            } catch (e: Exception) {
                AppLog.w("UsbManager", "Error closing UsbControlBlock: ${e.message}")
            }
        }
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
        AppLog.i("State", "[Session #$currentSessionId] STATE: $oldState → $newState")
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
