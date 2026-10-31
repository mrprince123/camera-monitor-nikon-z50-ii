package com.example.cameramonitor.camera.wifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
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
import com.example.cameramonitor.camera.usb.ptp.PtpConstants
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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Wireless Nikon PTP/IP Backend (ISO 15740 over TCP/IP).
 * Connects to Nikon camera Wi-Fi access point on port 15740.
 */
class WifiPtpCameraBackend(
    private val context: Context,
    val cameraIp: String = "192.168.1.1",
    val port: Int = 15740
) : CameraBackend {

    override val backendType = CameraDevice.ConnectionType.WIFI_PTP
    override val capabilities = CameraCapabilities.PTP_DEFAULT

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _properties = MutableStateFlow<Map<CameraPropertyType, CameraPropertyValue>>(emptyMap())
    override val properties: StateFlow<Map<CameraPropertyType, CameraPropertyValue>> = _properties.asStateFlow()

    private val _isMovieRecording = MutableStateFlow(false)
    override val isMovieRecording: StateFlow<Boolean> = _isMovieRecording.asStateFlow()

    private val _afTargetState = MutableStateFlow<AfTargetState?>(null)
    override val afTargetState: StateFlow<AfTargetState?> = _afTargetState.asStateFlow()

    private var cmdSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null

    @Volatile private var activeSessionId = 0L
    @Volatile private var activeSurfaceTexture: SurfaceTexture? = null
    @Volatile private var renderSurface: Surface? = null

    private var liveViewJob: Job? = null
    private var txCounter = 1

    @Volatile var totalFrames = 0L; private set
    @Volatile var currentFps = 0; private set

    override val diagnosticSummary: String
        get() = "Nikon PTP/IP [Wi-Fi $cameraIp:$port] | Frames: $totalFrames ($currentFps fps)"

    /**
     * Connects to camera via Wi-Fi PTP/IP handshake.
     */
    override suspend fun connect(device: UsbDevice, sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        connectWifi(cameraIp, port, sessionId)
    }

    suspend fun connectWifi(ip: String = cameraIp, portNum: Int = port, sessionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        AppLog.i("WifiPtp", "[Session #$sessionId] Connecting to Nikon camera at $ip:$portNum via PTP/IP...")
        activeSessionId = sessionId
        _state.value = CameraState.Connecting("Opening TCP connection to $ip:$portNum...")

        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, portNum), 4000)
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            cmdSocket = socket
            cmdIn = input
            cmdOut = output

            // PTP/IP Step 1: Init Command Request (Type 1)
            AppLog.i("WifiPtp", "[Session #$sessionId] Sending PTP/IP Init Command Request...")
            val guid = UUID.randomUUID()
            val friendlyName = "CamMonitor"
            val nameBytes = friendlyName.toByteArray(Charsets.UTF_16LE)

            val initReqLen = 8 + 16 + nameBytes.size + 2 + 4
            val bb = ByteBuffer.allocate(initReqLen).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(initReqLen)
            bb.putInt(1) // Packet Type 1 = Init Command Request
            bb.putLong(guid.mostSignificantBits)
            bb.putLong(guid.leastSignificantBits)
            bb.put(nameBytes)
            bb.putShort(0) // Null terminator
            bb.putInt(0x00010000) // PTP/IP version 1.0

            output.write(bb.array())
            output.flush()

            // Read Init Command Ack
            val ackHeader = ByteArray(8)
            input.read(ackHeader)
            val ackLen = ByteBuffer.wrap(ackHeader).order(ByteOrder.LITTLE_ENDIAN).int
            val ackPayload = ByteArray(ackLen - 8)
            input.read(ackPayload)

            val serverConnNum = ByteBuffer.wrap(ackPayload).order(ByteOrder.LITTLE_ENDIAN).int
            AppLog.i("WifiPtp", "[Session #$sessionId] PTP/IP Handshake Ack! Server connection #$serverConnNum")

            // PTP/IP Step 2: Open PTP Session (0x1002)
            sendPtpCommand(output, input, PtpConstants.CMD_OPEN_SESSION, 1)

            // Step 3: Start Nikon Live View (0x9201)
            sendPtpCommand(output, input, PtpConstants.NIKON_CMD_START_LIVE_VIEW)

            val cameraDev = CameraDevice(
                deviceId = 1,
                deviceName = "Nikon Z50 II (Wi-Fi)",
                vendorId = 0x04B0,
                productId = 0x0455,
                productName = "Nikon Z50 II (Wi-Fi PTP/IP)",
                connectionType = CameraDevice.ConnectionType.WIFI_PTP
            )

            _state.value = CameraState.Connected(cameraDev, capabilities)

            startLiveViewLoop(sessionId, output, input)
            Result.success(Unit)
        } catch (e: Exception) {
            val err = "Wi-Fi connection error: ${e.message}"
            AppLog.e("WifiPtp", "[Session #$sessionId] $err", e)
            disconnect(err)
            _state.value = CameraState.Error(err)
            Result.failure(e)
        }
    }

    private fun sendPtpCommand(out: OutputStream, `in`: InputStream, code: Int, vararg params: Int): Int {
        val txId = txCounter++
        val len = 18 + params.size * 4
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len)
        bb.putInt(6) // Type 6 = Operation Request
        bb.putInt(1) // Data phase info
        bb.putShort(code.toShort())
        bb.putInt(txId)
        for (p in params) bb.putInt(p)

        out.write(bb.array())
        out.flush()

        val respHeader = ByteArray(8)
        `in`.read(respHeader)
        val respLen = ByteBuffer.wrap(respHeader).order(ByteOrder.LITTLE_ENDIAN).int
        val respPayload = ByteArray((respLen - 8).coerceAtLeast(0))
        if (respPayload.isNotEmpty()) `in`.read(respPayload)
        return respLen
    }

    private fun startLiveViewLoop(sessionId: Long, out: OutputStream, `in`: InputStream) {
        liveViewJob?.cancel()
        liveViewJob = scope.launch {
            AppLog.i("WifiPtp", "[Session #$sessionId] Starting Wi-Fi Live View loop...")
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val srcRect = Rect()
            val dstRect = RectF()

            while (isActive && sessionId == activeSessionId) {
                try {
                    // Send 0x9203
                    val txId = txCounter++
                    val bb = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
                    bb.putInt(18)
                    bb.putInt(6)
                    bb.putInt(1)
                    bb.putShort(PtpConstants.NIKON_CMD_GET_LIVE_VIEW_IMAGE.toShort())
                    bb.putInt(txId)

                    out.write(bb.array())
                    out.flush()

                    // Read packet
                    val header = ByteArray(8)
                    `in`.read(header)
                    val pktLen = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                    if (pktLen > 64) {
                        val payload = ByteArray(pktLen - 8)
                        var read = 0
                        while (read < payload.size) {
                            val r = `in`.read(payload, read, payload.size - read)
                            if (r <= 0) break
                            read += r
                        }

                        // Scan for SOI 0xFF, 0xD8
                        var jpegStart = -1
                        for (i in 0 until minOf(payload.size - 1, 512)) {
                            if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD8.toByte()) {
                                jpegStart = i
                                break
                            }
                        }

                        if (jpegStart >= 0) {
                            val bmp = BitmapFactory.decodeByteArray(payload, jpegStart, payload.size - jpegStart)
                            if (bmp != null) {
                                totalFrames++
                                val surface = renderSurface
                                if (surface != null && surface.isValid) {
                                    val canvas = surface.lockCanvas(null)
                                    if (canvas != null) {
                                        srcRect.set(0, 0, bmp.width, bmp.height)
                                        dstRect.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
                                        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
                                        surface.unlockCanvasAndPost(canvas)
                                    }
                                }
                                bmp.recycle()
                            }
                        }
                    }
                    delay(33) // ~30 fps
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(50)
                }
            }
        }
    }

    override suspend fun disconnect(reason: String) = withContext(Dispatchers.IO) {
        AppLog.i("WifiPtp", "Disconnecting WifiPtpCameraBackend (reason: $reason)...")
        activeSessionId++
        liveViewJob?.cancel()
        liveViewJob = null

        try { cmdIn?.close() } catch (_: Exception) {}
        try { cmdOut?.close() } catch (_: Exception) {}
        try { cmdSocket?.close() } catch (_: Exception) {}

        cmdSocket = null
        cmdIn = null
        cmdOut = null

        renderSurface?.release()
        renderSurface = null
        _state.value = CameraState.Disconnected
    }

    override fun setPreviewSurface(surfaceTexture: SurfaceTexture?) {
        this.activeSurfaceTexture = surfaceTexture
        renderSurface?.release()
        renderSurface = if (surfaceTexture != null) Surface(surfaceTexture) else null
    }

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        val out = cmdOut ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        val `in` = cmdIn ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        try {
            sendPtpCommand(out, `in`, PtpConstants.NIKON_CMD_CAPTURE, 0xFFFFFFFF.toInt())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

