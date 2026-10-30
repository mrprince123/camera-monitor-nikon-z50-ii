package com.example.cameramonitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera

/**
 * Centralized USB Connection Manager for CamMonitor.
 *
 * Implements a strict, single-owner state machine:
 *   DISCONNECTED → DETECTED → PERMISSION_PENDING → PERMISSION_GRANTED → OPENING → STREAMING
 *
 * Key guarantees:
 *  1. No automatic USB permission resetting (never calls resetDevicePermission).
 *  2. Type-safe Parcelable extraction for Android 13–16 (Tiramisu to Android 16 API 36).
 *  3. Fully asynchronous permission handling via PendingIntent broadcast.
 *  4. Single component ownership: deduplicates attach/onNewIntent/onResume events.
 *  5. Direct UVCCamera control rendering directly to SurfaceTexture (no conflicting EGL).
 *  6. Preserves PTP: only binds to USB interfaces with class=14 (UVC Video).
 *  7. Microsecond timestamped logging via AppLog.
 */
class UsbCameraManager(
    private val context: Context,
    private val stateListener: (oldState: UsbConnectionState, newState: UsbConnectionState) -> Unit
) {

    enum class UsbConnectionState {
        DISCONNECTED,
        DETECTED,
        PERMISSION_PENDING,
        PERMISSION_GRANTED,
        OPENING,
        STREAMING,
        ERROR
    }

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

    // Active connection state
    private var activeDevice: UsbDevice? = null
    private var activeCtrlBlock: USBMonitor.UsbControlBlock? = null
    private var uvcCamera: UVCCamera? = null
    private var surfaceTexture: SurfaceTexture? = null

    // USBMonitor used ONLY as a factory for UsbControlBlock (never calling monitor.register())
    private val dummyMonitor = USBMonitor.getInstance(context).apply {
        setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {}
            override fun onDetach(device: UsbDevice?) {}
            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
            override fun onCancel(device: UsbDevice?) {}
        })
    }

    // Broadcast receiver for USB attach/detach and permission results
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            AppLog.i("UsbManager", "onReceive() action=$action")

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
        AppLog.i("UsbManager", "UsbCameraManager started, receiver registered")

        // Initial scan for already attached devices
        scanForConnectedDevices()
    }

    fun stop() {
        AppLog.i("UsbManager", "Stopping UsbCameraManager...")
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        closeCamera(reason = "manager stopped")
        transitionTo(UsbConnectionState.DISCONNECTED)
    }

    fun setPreviewSurface(st: SurfaceTexture?) {
        this.surfaceTexture = st
        AppLog.i("UsbManager", "setPreviewSurface: available=${st != null}, state=$state")
        if (st != null && state == UsbConnectionState.PERMISSION_GRANTED) {
            startStreamInternal()
        }
    }

    /**
     * Called from MainActivity onNewIntent / onResume to handle attached device without duplicates.
     */
    fun onNewIntentAttachedDevice(device: UsbDevice?) {
        if (device != null) {
            handleDeviceAttached(device, source = "NEW_INTENT")
        } else {
            scanForConnectedDevices()
        }
    }

    /**
     * Checks currently connected USB devices.
     */
    fun scanForConnectedDevices() {
        val deviceList = usbManager.deviceList
        AppLog.d("UsbManager", "Scanning deviceList (found ${deviceList.size} devices)")
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

        // 2. Prevent duplicate initialization if already active for this exact device
        if ((state == UsbConnectionState.OPENING || state == UsbConnectionState.STREAMING) &&
            activeDevice?.deviceId == device.deviceId) {
            AppLog.i("UsbManager", "Ignoring duplicate attach event: camera already $state")
            return
        }

        activeDevice = device
        transitionTo(UsbConnectionState.DETECTED)

        // 3. Asynchronously request permission if not already granted
        if (usbManager.hasPermission(device)) {
            AppLog.i("UsbManager", "Permission already granted by OS for ${device.deviceName}")
            transitionTo(UsbConnectionState.PERMISSION_GRANTED)
            if (surfaceTexture != null) {
                startStreamInternal()
            } else {
                AppLog.i("UsbManager", "Waiting for SurfaceTexture to be created before opening UVC...")
            }
        } else {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        transitionTo(UsbConnectionState.PERMISSION_PENDING)
        AppLog.i("UsbManager", "Requesting OS USB permission for ${device.deviceName}...")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handlePermissionResult(intent: Intent, device: UsbDevice?) {
        val targetDevice = device ?: activeDevice
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        AppLog.i("UsbManager", "Permission broadcast received: granted=$granted, device=${targetDevice?.deviceName}")

        if (targetDevice == null) {
            AppLog.e("UsbManager", "Permission result received with null device reference!")
            transitionTo(UsbConnectionState.ERROR)
            return
        }

        if (granted) {
            activeDevice = targetDevice
            transitionTo(UsbConnectionState.PERMISSION_GRANTED)
            if (surfaceTexture != null) {
                startStreamInternal()
            } else {
                AppLog.i("UsbManager", "Permission granted; waiting for SurfaceTexture before opening...")
            }
        } else {
            AppLog.w("UsbManager", "USB permission denied by user for ${targetDevice.deviceName}")
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        AppLog.w("UsbManager", "Device detached: ${device.deviceName} (active=${activeDevice?.deviceName})")
        if (activeDevice?.deviceId == device.deviceId) {
            closeCamera(reason = "device physically disconnected")
            activeDevice = null
            transitionTo(UsbConnectionState.DISCONNECTED)
        }
    }

    @Synchronized
    private fun startStreamInternal() {
        val device = activeDevice
        val st = surfaceTexture

        if (device == null) {
            AppLog.e("UsbManager", "startStreamInternal failed: activeDevice is null")
            return
        }
        if (st == null) {
            AppLog.e("UsbManager", "startStreamInternal failed: surfaceTexture is null")
            return
        }

        if (state == UsbConnectionState.STREAMING || state == UsbConnectionState.OPENING) {
            AppLog.i("UsbManager", "Stream already $state, skipping redundant start")
            return
        }

        transitionTo(UsbConnectionState.OPENING)
        AppLog.i("UsbManager", "Opening UVC camera connection for ${device.deviceName} (${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG)...")

        Thread {
            try {
                // 1. Obtain UsbControlBlock safely without starting USBMonitor's polling thread
                val ctrlBlock = dummyMonitor.openDevice(device)
                if (ctrlBlock == null) {
                    AppLog.e("UsbManager", "Failed to open UsbControlBlock for ${device.deviceName}")
                    mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
                    return@Thread
                }
                activeCtrlBlock = ctrlBlock
                val fd = ctrlBlock.fileDescriptor
                AppLog.i("UsbManager", "openDevice SUCCESS: fd=$fd, busNum=${ctrlBlock.busNum}, devNum=${ctrlBlock.devNum}")

                // 2. Open UVCCamera directly
                val camera = UVCCamera()
                camera.open(ctrlBlock)
                AppLog.i("UsbManager", "native UVCCamera.open() SUCCESS (UVC interface claimed, native connection established)")

                // 3. Configure 1280x720 MJPEG frame size
                camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                AppLog.i("UsbManager", "UVC preview size set to ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} @ MJPEG")

                // 4. Bind directly to SurfaceTexture (no conflicting EGL window surface)
                camera.setPreviewTexture(st)
                camera.startPreview()
                uvcCamera = camera

                AppLog.i("UsbManager", "UVC stream started successfully!")
                mainHandler.post { transitionTo(UsbConnectionState.STREAMING) }

            } catch (e: Exception) {
                AppLog.e("UsbManager", "Exception during UVC stream startup: ${e.message}", e)
                closeCamera(reason = "startup exception")
                mainHandler.post { transitionTo(UsbConnectionState.ERROR) }
            }
        }.start()
    }

    @Synchronized
    private fun closeCamera(reason: String) {
        AppLog.i("UsbManager", "closeCamera() reason: $reason [uvcCamera=${uvcCamera != null}, ctrlBlock=${activeCtrlBlock != null}]")
        try {
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
        stateListener(oldState, newState)
    }
}
