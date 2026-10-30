package com.example.cameramonitor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.example.cameramonitor.databinding.ActivityMainBinding
import kotlin.math.atan2
import kotlin.math.abs

/**
 * Main activity — extends CameraActivity from AndroidUSBCamera library.
 *
 * ⚠️ EMULATOR NOTE:
 * Android emulators do NOT support USB host mode. There is no workaround.
 * This app must run on a REAL Android phone with USB OTG/host support.
 * Connect:  Camera → USB-C cable → Android Phone  (NOT the Mac)
 *
 * Camera setup (Nikon Z50 II):
 *   Menu → Setup → USB → UVC / Webcam mode
 */
class MainActivity : CameraActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var textureView: AspectRatioTextureView? = null

    // ── Sensor ─────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager

    // ── Background analysis ────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var frameBmp: Bitmap? = null
    private var maskBmp: Bitmap? = null
    private var pixels = IntArray(0)
    private var luma = IntArray(0)
    private var maskPx = IntArray(0)
    @Volatile private var running = false

    private val analysisWidth = 320
    private val zebraThreshold = 235
    private val peakThreshold  = 40

    // ── Controls visibility ────────────────────────────────────────────────
    private var controlsVisible = true

    // ── Resolution fallback state ──────────────────────────────────────────
    private var use720p = false   // flip to true if 1080p fails

    // ── Emulator detection ─────────────────────────────────────────────────
    private val isEmulator: Boolean by lazy {
        (Build.BRAND.startsWith("generic") ||
         Build.DEVICE.startsWith("generic") ||
         Build.FINGERPRINT.startsWith("generic") ||
         Build.FINGERPRINT.startsWith("unknown") ||
         Build.HARDWARE.contains("goldfish") ||
         Build.HARDWARE.contains("ranchu") ||
         Build.MODEL.contains("google_sdk") ||
         Build.MODEL.contains("Emulator") ||
         Build.MODEL.contains("Android SDK built") ||
         Build.MANUFACTURER.contains("Genymotion") ||
         Build.PRODUCT.contains("sdk") ||
         Build.PRODUCT.contains("vbox") ||
         Build.PRODUCT.contains("emulator") ||
         Build.PRODUCT.contains("simulator"))
    }

    // ──────────────────────────────────────────────────────────────────────
    //  CameraActivity required overrides
    // ──────────────────────────────────────────────────────────────────────

    override fun getRootView(layoutInflater: LayoutInflater): View {
        binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun getCameraView(): IAspectRatio =
        AspectRatioTextureView(this).also { textureView = it }

    override fun getCameraViewContainer(): ViewGroup = binding.cameraViewContainer

    /**
     * Request MJPEG preview. Falls back to 720p after first error.
     * FORMAT_MJPEG is required for Nikon UVC webcam mode.
     */
    override fun getCameraRequest(): CameraRequest =
        CameraRequest.Builder()
            .setPreviewWidth(if (use720p) 1280 else 1920)
            .setPreviewHeight(if (use720p) 720  else 1080)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .create()

    // ──────────────────────────────────────────────────────────────────────
    //  initView
    // ──────────────────────────────────────────────────────────────────────

    override fun initView() {
        super.initView()
        setupWindowFlags()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Start background analysis thread
        analysisThread = HandlerThread("cam-analysis").also {
            it.start()
            analysisHandler = Handler(it.looper)
        }

        // Wire overlay toggles
        val o = binding.overlay
        binding.tgGrid.setOnCheckedChangeListener  { _, v -> o.showGrid  = v; o.invalidate() }
        binding.tgLevel.setOnCheckedChangeListener { _, v -> o.showLevel = v; o.invalidate() }
        binding.tgHist.setOnCheckedChangeListener  { _, v -> o.showHist  = v; o.invalidate() }
        binding.tgZebra.setOnCheckedChangeListener { _, v -> o.showZebra = v; o.invalidate() }
        binding.tgPeak.setOnCheckedChangeListener  { _, v -> o.showPeak  = v; o.invalidate() }

        // Tap anywhere → show / hide controls
        binding.root.setOnClickListener { toggleControls() }

        // Show appropriate splash message immediately
        if (isEmulator) {
            showEmulatorWarning()
        } else {
            showSplash("Connect your camera")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (isEmulator) {
            // Don't attempt USB access on emulator — it will crash or hang
            showEmulatorWarning()
            return
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        running = true
        analysisHandler?.post(analysisLoop)
    }

    override fun onPause() {
        super.onPause()
        running = false
        analysisHandler?.removeCallbacks(analysisLoop)
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        running = false
        analysisHandler?.removeCallbacksAndMessages(null)
        analysisThread?.quitSafely()
        analysisThread = null
        analysisHandler = null
        frameBmp?.recycle(); frameBmp = null
        maskBmp?.recycle();  maskBmp  = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Window setup
    // ──────────────────────────────────────────────────────────────────────

    private fun setupWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Camera state callbacks
    // ──────────────────────────────────────────────────────────────────────

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        runOnUiThread {
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    use720p = false  // reset on successful open
                    hideSplash()
                }
                ICameraStateCallBack.State.CLOSED -> {
                    showSplash("Connect your camera\n\nCamera → USB-C → this phone")
                }
                ICameraStateCallBack.State.ERROR -> {
                    val errMsg = msg ?: "unknown"
                    if (!use720p && (errMsg.contains("1920") || errMsg.contains("format", true)
                            || errMsg.contains("resolution", true) || errMsg.contains("open", true))) {
                        // 1080p rejected — try 720p automatically
                        use720p = true
                        showSplash("Retrying at 720p…")
                        mainHandler.postDelayed({ restartCamera() }, 800)
                    } else {
                        showSplash(buildErrorHelp(errMsg))
                    }
                }
            }
        }
    }

    /** Attempt a camera restart (e.g. after resolution fallback). */
    private fun restartCamera() {
        if (isFinishing || isDestroyed) return
        try {
            closeCamera()
            mainHandler.postDelayed({ openCamera() }, 500)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun buildErrorHelp(raw: String): String =
        "⚠ Camera error\n\n" +
        "• Set camera USB mode to UVC/Webcam\n" +
        "• Use a data-capable USB-C cable\n" +
        "• Tap screen to retry\n\n" +
        "(${raw.take(60)})"

    // ──────────────────────────────────────────────────────────────────────
    //  Splash helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun showSplash(message: String) {
        binding.statusText.text = message
        binding.splashEmulatorNote.visibility = View.GONE
        if (binding.splashLayout.visibility == View.VISIBLE) return
        binding.splashLayout.alpha = 0f
        binding.splashLayout.visibility = View.VISIBLE
        binding.splashLayout.animate().alpha(1f).setDuration(400).start()
    }

    private fun showEmulatorWarning() {
        binding.statusText.text = "Emulator detected"
        binding.splashEmulatorNote.visibility = View.VISIBLE
        binding.splashLayout.alpha = 1f
        binding.splashLayout.visibility = View.VISIBLE
    }

    private fun hideSplash() {
        if (binding.splashLayout.visibility != View.VISIBLE) return
        binding.splashLayout.animate()
            .alpha(0f)
            .setDuration(400)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.splashLayout.visibility = View.GONE
                }
            })
            .start()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Controls visibility toggle (smooth fade)
    // ──────────────────────────────────────────────────────────────────────

    private fun toggleControls() {
        // If on emulator, tapping retries nothing — just return
        if (isEmulator) return
        controlsVisible = !controlsVisible
        val target = if (controlsVisible) 1f else 0f
        binding.controls.animate()
            .alpha(target)
            .setDuration(200)
            .withEndAction {
                binding.controls.visibility =
                    if (controlsVisible) View.VISIBLE else View.INVISIBLE
            }
            .start()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Sensor — phone tilt for digital level line
    // ──────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(e: SensorEvent) {
        var a = Math.toDegrees(atan2(e.values[1].toDouble(), e.values[0].toDouble())).toFloat()
        if (a > 90f) a -= 180f else if (a < -90f) a += 180f
        val o = binding.overlay
        o.tiltDeg = o.tiltDeg * 0.8f + a * 0.2f
        if (o.showLevel) o.invalidate()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    // ──────────────────────────────────────────────────────────────────────
    //  Background analysis loop (~7 fps)
    // ──────────────────────────────────────────────────────────────────────

    private val analysisLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val o = binding.overlay
            if (o.showHist || o.showZebra || o.showPeak) analyzeFrame()
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    updateVideoRect()
                }
            }
            analysisHandler?.postDelayed(this, 150)
        }
    }

    private fun updateVideoRect() {
        val tv = textureView ?: return
        if (tv.width == 0 || tv.height == 0) return
        val a = IntArray(2); tv.getLocationOnScreen(a)
        val b = IntArray(2); binding.overlay.getLocationOnScreen(b)
        val l = (a[0] - b[0]).toFloat()
        val t = (a[1] - b[1]).toFloat()
        binding.overlay.videoRect.set(l, t, l + tv.width, t + tv.height)
        binding.overlay.invalidate()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Frame analysis: histogram + zebra + focus peaking
    // ──────────────────────────────────────────────────────────────────────

    private fun analyzeFrame() {
        val tv = textureView ?: return
        if (!tv.isAvailable || tv.width == 0 || tv.height == 0) return

        val w = analysisWidth
        val h = (w.toFloat() * tv.height / tv.width).toInt().coerceAtLeast(2)

        if (frameBmp?.width != w || frameBmp?.height != h) {
            frameBmp?.recycle()
            maskBmp?.recycle()
            frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            maskBmp  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels   = IntArray(w * h)
            luma     = IntArray(w * h)
            maskPx   = IntArray(w * h)
        }

        val bmp = frameBmp ?: return
        try { tv.getBitmap(bmp) } catch (_: Exception) { return }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val o = binding.overlay
        val hist = IntArray(64)
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (0.299f * Color.red(p) + 0.587f * Color.green(p) +
                     0.114f * Color.blue(p)).toInt()
            luma[i] = y
            hist[y shr 2]++
        }
        mainHandler.post {
            if (!isFinishing && !isDestroyed) { o.hist = hist; o.invalidate() }
        }

        if (o.showZebra || o.showPeak) {
            java.util.Arrays.fill(maskPx, 0)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    when {
                        o.showZebra && luma[i] >= zebraThreshold && ((x + y) and 7) < 4 ->
                            maskPx[i] = 0xCCFF2222.toInt()
                        o.showPeak -> {
                            val gx = abs(luma[i + 1] - luma[i - 1])
                            val gy = abs(luma[i + w] - luma[i - w])
                            if (gx + gy > peakThreshold) maskPx[i] = 0xFF00FF88.toInt()
                        }
                    }
                }
            }
            maskBmp?.setPixels(maskPx, 0, w, 0, 0, w, h)
            val snapshot = maskBmp?.copy(Bitmap.Config.ARGB_8888, false)
            mainHandler.post {
                if (!isFinishing && !isDestroyed) { o.mask = snapshot; o.invalidate() }
            }
        }
    }
}
