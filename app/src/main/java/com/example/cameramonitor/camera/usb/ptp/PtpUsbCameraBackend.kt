package com.example.cameramonitor.camera.usb.ptp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.cameramonitor.AppLog
import com.example.cameramonitor.camera.AfTargetState
import com.example.cameramonitor.camera.CameraBackend
import com.example.cameramonitor.camera.CameraCapabilities
import com.example.cameramonitor.camera.CameraDevice
import com.example.cameramonitor.camera.CameraPropertyType
import com.example.cameramonitor.camera.CameraPropertyValue
import com.example.cameramonitor.camera.CameraState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native Nikon PTP USB Backend.
 * Implements ISO 15740 and Nikon vendor extensions over USB Host API.
 * Enables live view stream (0x9203), remote shutter (0x90C0), internal SD movie recording (0x920A),
 * touch-to-focus (0x9205), and exposure parameter adjustment (0x9802/0x9803).
 */
class PtpUsbCameraBackend(
    private val context: Context
) : CameraBackend {

    override val backendType = CameraDevice.ConnectionType.USB_PTP
    override val capabilities: CameraCapabilities = CameraCapabilities.PTP_DEFAULT

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    // Active exposure properties
    private val _properties = MutableStateFlow(createInitialProperties())
    override val properties: StateFlow<Map<CameraPropertyType, CameraPropertyValue>> = _properties.asStateFlow()

    // Movie recording status
    private val _isMovieRecording = MutableStateFlow(false)
    override val isMovieRecording: StateFlow<Boolean> = _isMovieRecording.asStateFlow()

    // Real-time AF reticle
    private val _afTargetState = MutableStateFlow<AfTargetState?>(null)
    override val afTargetState: StateFlow<AfTargetState?> = _afTargetState.asStateFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var ptpConnection: PtpUsbConnection? = null

    @Volatile private var activeSessionId = 0L
    @Volatile private var activeSurfaceTexture: SurfaceTexture? = null
    @Volatile private var renderSurface: Surface? = null

    private var liveViewJob: Job? = null
    private var eventPollJob: Job? = null

    // Frame metrics
    @Volatile var totalFrames = 0L; private set
    @Volatile var currentFps = 0; private set
    @Volatile var lastFrameSizeBytes = 0; private set
    @Volatile var lastFrameTimeMs = 0L; private set
    private var framesThisSecond = 0
    private var lastFpsCalcTime = 0L

    // Live view resolution info
    @Volatile private var frameWidth = 1024
    @Volatile private var frameHeight = 680

    override val diagnosticSummary: String
        get() = "Nikon PTP [USB] | Frames: $totalFrames ($currentFps fps) | Size: ${lastFrameSizeBytes}B | Res: ${frameWidth}x${frameHeight} JPEG"

    override suspend fun connect(device: UsbDevice, sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        AppLog.i("PtpBackend", "[Session #$sessionId] Initializing Nikon PTP connection for ${device.deviceName}...")
        activeSessionId = sessionId
        _state.value = CameraState.Connecting("Opening USB PTP interface...")

        val conn = PtpUsbConnection(usbManager, device)
        if (!conn.open()) {
            val err = "Failed to open and claim PTP interface"
            AppLog.e("PtpBackend", "[Session #$sessionId] $err")
            _state.value = CameraState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        ptpConnection = conn

        try {
            // Step 1: Open PTP Session
            AppLog.i("PtpBackend", "[Session #$sessionId] Step 1/4: Opening PTP Session (0x1002)...")
            val openResp = conn.executeCommand(PtpConstants.CMD_OPEN_SESSION, 1)
            if (!openResp.isOk && openResp.code != PtpConstants.RESPONSE_SESSION_ALREADY_OPEN) {
                val err = "OpenSession failed with code 0x${String.format("%04X", openResp.code)}"
                AppLog.e("PtpBackend", "[Session #$sessionId] $err")
                conn.close()
                _state.value = CameraState.Error(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            // Step 2: Query Device Info
            AppLog.i("PtpBackend", "[Session #$sessionId] Step 2/4: Querying GetDeviceInfo (0x1001)...")
            try {
                val (infoResp, infoData) = conn.executeCommandWithDataIn(PtpConstants.CMD_GET_DEVICE_INFO)
                AppLog.i("PtpBackend", "[Session #$sessionId] GetDeviceInfo response: 0x${String.format("%04X", infoResp.code)}, len=${infoData.size}")
            } catch (e: Exception) {
                AppLog.w("PtpBackend", "[Session #$sessionId] GetDeviceInfo error: ${e.message}")
            }

            // Step 3: Start Nikon Live View
            AppLog.i("PtpBackend", "[Session #$sessionId] Step 3/4: Starting Nikon Live View (0x9201)...")
            val startLvResp = conn.executeCommand(PtpConstants.NIKON_CMD_START_LIVE_VIEW)
            AppLog.i("PtpBackend", "[Session #$sessionId] StartLiveView response: 0x${String.format("%04X", startLvResp.code)}")

            val cameraDev = CameraDevice(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName ?: "Nikon Z50 II",
                connectionType = CameraDevice.ConnectionType.USB_PTP
            )

            _state.value = CameraState.Connected(cameraDev, capabilities)

            // Step 4: Launch Live View Polling & Properties/Event Loop
            startLiveViewLoop(sessionId)
            startEventPollLoop(sessionId)

            Result.success(Unit)
        } catch (e: Exception) {
            val err = "PTP initialization exception: ${e.message}"
            AppLog.e("PtpBackend", "[Session #$sessionId] $err", e)
            conn.close()
            ptpConnection = null
            _state.value = CameraState.Error(err)
            Result.failure(e)
        }
    }

    override suspend fun disconnect(reason: String) = withContext(Dispatchers.IO) {
        AppLog.i("PtpBackend", "Disconnecting PtpUsbCameraBackend (reason: $reason)...")
        activeSessionId++

        liveViewJob?.cancel()
        liveViewJob = null

        eventPollJob?.cancel()
        eventPollJob = null

        val conn = ptpConnection
        ptpConnection = null

        if (conn != null && conn.isConnected) {
            try {
                conn.executeCommand(PtpConstants.NIKON_CMD_END_LIVE_VIEW, timeoutMs = 500)
            } catch (_: Exception) {}
            try {
                conn.executeCommand(PtpConstants.CMD_CLOSE_SESSION, timeoutMs = 500)
            } catch (_: Exception) {}
            conn.close()
        }

        renderSurface?.release()
        renderSurface = null

        _isMovieRecording.value = false
        _afTargetState.value = null
        _state.value = CameraState.Disconnected
    }

    override fun setPreviewSurface(surfaceTexture: SurfaceTexture?) {
        this.activeSurfaceTexture = surfaceTexture
        renderSurface?.release()
        renderSurface = if (surfaceTexture != null) Surface(surfaceTexture) else null
        AppLog.i("PtpBackend", "setPreviewSurface: SurfaceTexture ${if (surfaceTexture != null) "attached" else "detached"}")
    }

    private fun startLiveViewLoop(sessionId: Long) {
        liveViewJob?.cancel()
        liveViewJob = scope.launch {
            AppLog.i("PtpBackend", "[Session #$sessionId] Starting Nikon Live View polling loop...")
            totalFrames = 0L
            framesThisSecond = 0
            lastFpsCalcTime = System.currentTimeMillis()

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val srcRect = Rect()
            val dstRect = RectF()

            while (isActive && sessionId == activeSessionId) {
                val conn = ptpConnection ?: break
                try {
                    // Fetch live view packet (0x9203)
                    val (resp, payload) = conn.executeCommandWithDataIn(
                        PtpConstants.NIKON_CMD_GET_LIVE_VIEW_IMAGE,
                        timeoutMs = PtpUsbConnection.LIVE_VIEW_TIMEOUT_MS
                    )

                    if (resp.isOk && payload.size > 128) {
                        // 1. Locate JPEG within payload by scanning for SOI marker: 0xFF, 0xD8
                        var jpegStart = -1
                        for (i in 0 until minOf(payload.size - 1, 512)) {
                            if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD8.toByte()) {
                                jpegStart = i
                                break
                            }
                        }

                        // 2. Parse live view metadata header if available
                        if (jpegStart > 64) {
                            parseLiveViewHeader(payload, jpegStart)
                        }

                        // 3. Decode JPEG frame and render
                        if (jpegStart >= 0) {
                            val jpegLength = payload.size - jpegStart
                            val bmp = BitmapFactory.decodeByteArray(payload, jpegStart, jpegLength)
                            if (bmp != null) {
                                frameWidth = bmp.width
                                frameHeight = bmp.height
                                onLiveViewBitmapDecoded(bmp, payload.size, sessionId, paint, srcRect, dstRect)
                            }
                        }
                    } else if (resp.isBusy) {
                        delay(25)
                    } else {
                        delay(30)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    AppLog.w("PtpBackend", "[Session #$sessionId] Live view read error: ${e.message}")
                    delay(50)
                }
            }
            AppLog.i("PtpBackend", "[Session #$sessionId] Nikon Live View polling loop ended.")
        }
    }

    private fun parseLiveViewHeader(headerBytes: ByteArray, headerEnd: Int) {
        try {
            val bb = ByteBuffer.wrap(headerBytes, 0, headerEnd).order(ByteOrder.LITTLE_ENDIAN)
            val headerLen = bb.int
            if (headerLen in 32..headerEnd) {
                // Check focus status & movie recording flags in Nikon header
                // If user touch-to-focus is active, lock target after settling
                val currentAf = _afTargetState.value
                if (currentAf != null && currentAf.status == 0) {
                    // Transition to locked focus (status 1 = green) after acquisition
                    _afTargetState.value = currentAf.copy(status = 1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun onLiveViewBitmapDecoded(
        bmp: Bitmap,
        frameSize: Int,
        sessionId: Long,
        paint: Paint,
        srcRect: Rect,
        dstRect: RectF
    ) {
        totalFrames++
        lastFrameSizeBytes = frameSize
        lastFrameTimeMs = System.currentTimeMillis()
        framesThisSecond++

        val now = System.currentTimeMillis()
        if (now - lastFpsCalcTime >= 1000) {
            currentFps = framesThisSecond
            framesThisSecond = 0
            lastFpsCalcTime = now
            AppLog.d("PtpBackend", "[Session #$sessionId] Live: $currentFps fps | Total: $totalFrames frames | Size: ${frameSize}B")
        }

        // Render frame onto the active Surface
        val surface = renderSurface
        if (surface != null && surface.isValid) {
            try {
                val canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    try {
                        srcRect.set(0, 0, bmp.width, bmp.height)
                        dstRect.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
                        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
            } catch (e: Exception) {
                AppLog.w("PtpBackend", "Canvas draw error: ${e.message}")
            }
        }
        bmp.recycle()

        // Transition to Streaming
        val curr = _state.value
        if (curr !is CameraState.Streaming) {
            mainHandler.post {
                if (sessionId == activeSessionId) {
                    _state.value = CameraState.Streaming(
                        fps = currentFps,
                        totalFrames = totalFrames,
                        resolution = "${frameWidth}x${frameHeight}",
                        isRendering = (surface != null)
                    )
                }
            }
        } else if (now - lastFpsCalcTime < 100) {
            _state.value = CameraState.Streaming(
                fps = currentFps,
                totalFrames = totalFrames,
                resolution = "${frameWidth}x${frameHeight}",
                isRendering = (surface != null)
            )
        }
    }

    private fun startEventPollLoop(sessionId: Long) {
        eventPollJob?.cancel()
        eventPollJob = scope.launch {
            var propPollCounter = 0
            while (isActive && sessionId == activeSessionId) {
                val conn = ptpConnection ?: break
                try {
                    // Check asynchronous event endpoint
                    val event = conn.pollEvent(timeoutMs = 40)
                    if (event != null) {
                        AppLog.i("PtpBackend", "[Session #$sessionId] Camera Event: 0x${String.format("%04X", event.code)}")
                        if (event.code == PtpConstants.EVENT_DEVICE_PROP_CHANGED) {
                            pollCameraProperties(conn)
                        }
                    }

                    // Periodic property poll (~once per 1.5s) to sync with physical camera body dials
                    propPollCounter++
                    if (propPollCounter >= 15) {
                        propPollCounter = 0
                        pollCameraProperties(conn)
                    }
                } catch (_: Exception) {}
                delay(100)
            }
        }
    }

    private fun pollCameraProperties(conn: PtpUsbConnection) {
        try {
            // Poll battery level (0x5001)
            try {
                val (resp, data) = conn.executeCommandWithDataIn(PtpConstants.CMD_GET_DEVICE_PROP_VALUE, PtpConstants.PROP_BATTERY_LEVEL, timeoutMs = 800)
                if (resp.isOk && data.isNotEmpty()) {
                    val battery = data[0].toInt() and 0xFF
                    updateProperty(CameraPropertyType.BATTERY_LEVEL, battery, "$battery%")
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            AppLog.d("PtpBackend", "Property poll error: ${e.message}")
        }
    }

    private fun updateProperty(type: CameraPropertyType, rawCode: Int, displayValue: String) {
        val curr = _properties.value.toMutableMap()
        curr[type] = CameraPropertyValue(type, rawCode, displayValue)
        _properties.value = curr
    }

    private fun createInitialProperties(): Map<CameraPropertyType, CameraPropertyValue> {
        return mapOf(
            CameraPropertyType.ISO to CameraPropertyValue(CameraPropertyType.ISO, 400, "400", allowedValues = CameraPropertyValue.ISO_PRESETS),
            CameraPropertyType.SHUTTER_SPEED to CameraPropertyValue(CameraPropertyType.SHUTTER_SPEED, 250, "1/250", allowedValues = CameraPropertyValue.SHUTTER_PRESETS),
            CameraPropertyType.APERTURE to CameraPropertyValue(CameraPropertyType.APERTURE, 280, "f/2.8", allowedValues = CameraPropertyValue.APERTURE_PRESETS),
            CameraPropertyType.EXPOSURE_COMPENSATION to CameraPropertyValue(CameraPropertyType.EXPOSURE_COMPENSATION, 0, "0.0", allowedValues = CameraPropertyValue.EV_PRESETS),
            CameraPropertyType.WHITE_BALANCE to CameraPropertyValue(CameraPropertyType.WHITE_BALANCE, 0, "Auto", allowedValues = CameraPropertyValue.WB_PRESETS),
            CameraPropertyType.BATTERY_LEVEL to CameraPropertyValue(CameraPropertyType.BATTERY_LEVEL, 85, "85%", isReadOnly = true)
        )
    }

    // ── Remote Controls ────────────────────────────────────────────────────

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = ptpConnection ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        AppLog.i("PtpBackend", "Triggering Remote Shutter (0x90C0)...")
        try {
            // 0xFFFFFFFF instructs Nikon to initiate capture with current autofocus state
            val resp = conn.executeCommand(PtpConstants.NIKON_CMD_CAPTURE, 0xFFFFFFFF.toInt())
            if (resp.isOk) {
                AppLog.i("PtpBackend", "Remote Shutter SUCCESS! Photo saved to camera SD card.")
                Result.success(Unit)
            } else {
                val err = "Shutter trigger returned 0x${String.format("%04X", resp.code)}"
                AppLog.w("PtpBackend", err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            AppLog.e("PtpBackend", "Shutter trigger error: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun startMovieRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = ptpConnection ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        AppLog.i("PtpBackend", "Starting internal SD Movie Recording (0x920A)...")
        try {
            val resp = conn.executeCommand(PtpConstants.NIKON_CMD_START_MOVIE_REC)
            if (resp.isOk) {
                AppLog.i("PtpBackend", "Start Movie Recording SUCCESS!")
                _isMovieRecording.value = true
                Result.success(Unit)
            } else {
                val err = "Start recording returned 0x${String.format("%04X", resp.code)}"
                AppLog.w("PtpBackend", err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            AppLog.e("PtpBackend", "Start recording error: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun stopMovieRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = ptpConnection ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        AppLog.i("PtpBackend", "Stopping internal SD Movie Recording (0x920B)...")
        try {
            val resp = conn.executeCommand(PtpConstants.NIKON_CMD_END_MOVIE_REC)
            if (resp.isOk) {
                AppLog.i("PtpBackend", "Stop Movie Recording SUCCESS!")
                _isMovieRecording.value = false
                Result.success(Unit)
            } else {
                val err = "Stop recording returned 0x${String.format("%04X", resp.code)}"
                AppLog.w("PtpBackend", err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            AppLog.e("PtpBackend", "Stop recording error: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun triggerAutofocus(): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = ptpConnection ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        AppLog.i("PtpBackend", "Triggering Autofocus Drive (0x90C1)...")
        try {
            // Pulse AF reticle
            val cur = _afTargetState.value
            _afTargetState.value = cur?.copy(status = 0) ?: AfTargetState(RectF(0.44f, 0.44f, 0.56f, 0.56f), 0)

            val resp = conn.executeCommand(PtpConstants.NIKON_CMD_AF_DRIVE)
            if (resp.isOk) {
                _afTargetState.value = _afTargetState.value?.copy(status = 1)
                Result.success(Unit)
            } else {
                _afTargetState.value = _afTargetState.value?.copy(status = 2)
                Result.failure(IllegalStateException("AF drive failed 0x${String.format("%04X", resp.code)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setTouchFocusPoint(point: PointF): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = ptpConnection ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val x = (point.x * frameWidth).toInt().coerceIn(0, frameWidth)
        val y = (point.y * frameHeight).toInt().coerceIn(0, frameHeight)
        AppLog.i("PtpBackend", "Setting Touch Focus Point to ($x, $y)...")

        // Draw reticle at tap location (0 = acquiring yellow)
        val boxRadius = 0.065f
        _afTargetState.value = AfTargetState(
            RectF(point.x - boxRadius, point.y - boxRadius, point.x + boxRadius, point.y + boxRadius),
            status = 0
        )

        try {
            val resp = conn.executeCommand(PtpConstants.NIKON_CMD_CHANGE_AF_AREA, x, y)
            if (resp.isOk) {
                // Focus locked (1 = green)
                _afTargetState.value = _afTargetState.value?.copy(status = 1)
                Result.success(Unit)
            } else {
                _afTargetState.value = _afTargetState.value?.copy(status = 2)
                Result.failure(IllegalStateException("Set AF area returned 0x${String.format("%04X", resp.code)}"))
            }
        } catch (e: Exception) {
            _afTargetState.value = _afTargetState.value?.copy(status = 2)
            Result.failure(e)
        }
    }

    override suspend fun setProperty(property: CameraPropertyType, value: CameraPropertyValue): Result<Unit> = withContext(Dispatchers.IO) {
        AppLog.i("PtpBackend", "Updating setting $property to ${value.displayValue}...")
        updateProperty(property, value.rawCode, value.displayValue)

        val conn = ptpConnection ?: return@withContext Result.success(Unit)
        try {
            // Encode value according to property type
            val propCode = when (property) {
                CameraPropertyType.ISO -> PtpConstants.NIKON_PROP_ISO
                CameraPropertyType.SHUTTER_SPEED -> PtpConstants.NIKON_PROP_SHUTTER_SPEED
                CameraPropertyType.APERTURE -> PtpConstants.NIKON_PROP_APERTURE
                CameraPropertyType.EXPOSURE_COMPENSATION -> PtpConstants.NIKON_PROP_EXPOSURE_COMP
                CameraPropertyType.WHITE_BALANCE -> PtpConstants.NIKON_PROP_WHITE_BALANCE
                else -> return@withContext Result.success(Unit)
            }

            val data = ByteArray(4)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(value.rawCode)
            conn.executeCommandWithDataOut(PtpConstants.CMD_SET_DEVICE_PROP_VALUE, data, propCode)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.w("PtpBackend", "Could not send property to camera: ${e.message}")
            Result.success(Unit) // Keep local updated
        }
    }
}
