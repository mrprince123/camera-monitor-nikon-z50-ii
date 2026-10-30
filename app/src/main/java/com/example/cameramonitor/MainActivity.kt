package com.example.cameramonitor

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameramonitor.databinding.ActivityMainBinding
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Full-screen field monitor for the Nikon Z50 II (and any UVC/webcam-mode camera).
 *
 * Connect: Camera → USB-C data cable → this phone
 * Camera setting: Menu → Setup → USB → UVC / Webcam
 *
 * Overlays:
 *  • Rule-of-thirds grid
 *  • Digital level (accelerometer)
 *  • Luminance histogram
 *  • Zebra stripes (over-exposure warning)
 *  • Focus peaking (edge-detection highlight)
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var textureView: AspectRatioTextureView? = null
    private lateinit var usbCameraManager: UsbCameraManager

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
    private val zebraThreshold = 235   // luma 0-255; blown highlights
    private val peakThreshold = 40    // Sobel-like edge strength for peaking

    // ── Controls visibility ────────────────────────────────────────────────
    private var controlsVisible = true

    // ── Permissions ────────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        AppLog.i("Perm", "Permission result: RECORD_AUDIO=$audioGranted, CAMERA=$cameraGranted")
        if (audioGranted || cameraGranted) {
            usbCameraManager.scanForConnectedDevices()
        }
    }

    private fun checkPermissions() {
        val hasAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        AppLog.i("Perm", "Current permissions: RECORD_AUDIO=$hasAudio, CAMERA=$hasCamera")
        if (!hasAudio || !hasCamera) {
            AppLog.i("Perm", "Requesting missing permissions...")
            permissionLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Activity Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Lifecycle", "onCreate() started")
        setupWindowFlags()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initView()
    }

    private fun initView() {
        AppLog.i("App", "CamMonitor initView() ready")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Setup TextureView for video rendering
        val tv = AspectRatioTextureView(this).apply {
            setAspectRatio(UsbCameraManager.PREVIEW_WIDTH, UsbCameraManager.PREVIEW_HEIGHT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    AppLog.i("UI", "SurfaceTexture available (${width}x${height})")
                    usbCameraManager.setPreviewSurface(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    AppLog.d("UI", "SurfaceTexture size changed (${width}x${height})")
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    AppLog.i("UI", "SurfaceTexture destroyed")
                    usbCameraManager.setPreviewSurface(null)
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        this.textureView = tv
        binding.cameraViewContainer.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        // Initialize Centralized UsbCameraManager
        usbCameraManager = UsbCameraManager(this) { oldState, newState ->
            runOnUiThread {
                AppLog.d("UI", "State update UI: $oldState → $newState")
                when (newState) {
                    UsbCameraManager.UsbConnectionState.STREAMING -> {
                        hideSplash()
                        AppLog.i("UI", "Live camera stream is streaming on screen")
                    }
                    UsbCameraManager.UsbConnectionState.DISCONNECTED -> {
                        showSplash("Connect your camera\n\nCamera → USB-C → this phone")
                    }
                    UsbCameraManager.UsbConnectionState.DETECTED -> {
                        showSplash("Nikon Camera Detected…")
                    }
                    UsbCameraManager.UsbConnectionState.PERMISSION_PENDING -> {
                        showSplash("Requesting USB Permission…")
                    }
                    UsbCameraManager.UsbConnectionState.PERMISSION_GRANTED,
                    UsbCameraManager.UsbConnectionState.OPENING -> {
                        showSplash("Opening Camera (1280x720 MJPEG)…")
                    }
                    UsbCameraManager.UsbConnectionState.ERROR -> {
                        showSplash(
                            "⚠ Camera connection error\n\n" +
                            "• Set camera USB mode to UVC/Webcam\n" +
                            "• Reconnect USB-C data cable"
                        )
                    }
                }
            }
        }

        // Start dedicated background analysis thread
        analysisThread = HandlerThread("cam-analysis").also {
            it.start()
            analysisHandler = Handler(it.looper)
        }

        // Wire overlay toggle buttons
        val o = binding.overlay
        binding.tgGrid.setOnCheckedChangeListener  { _, v -> o.showGrid  = v; o.invalidate() }
        binding.tgLevel.setOnCheckedChangeListener { _, v -> o.showLevel = v; o.invalidate() }
        binding.tgHist.setOnCheckedChangeListener  { _, v -> o.showHist  = v; o.invalidate() }
        binding.tgZebra.setOnCheckedChangeListener { _, v -> o.showZebra = v; o.invalidate() }
        binding.tgPeak.setOnCheckedChangeListener  { _, v -> o.showPeak  = v; o.invalidate() }

        // Tap anywhere on background → smoothly show / hide controls
        binding.root.setOnClickListener { toggleControls() }

        // Wire Diagnostic Logs UI
        setupLogsUi()

        // Initial splash
        showSplash("Connect your camera")

        // Start USB manager & register receiver
        usbCameraManager.start()

        // Ensure runtime permissions
        checkPermissions()

        // Check if device was launched via USB_DEVICE_ATTACHED intent
        checkInitialIntent(intent)
    }

    private fun checkInitialIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        AppLog.i("Intent", "checkInitialIntent() action=$action")
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            usbCameraManager.onNewIntentAttachedDevice(device)
        }
    }

    private fun setupLogsUi() {
        AppLog.setListener { line ->
            runOnUiThread {
                if (binding.logModalContainer.visibility == View.VISIBLE) {
                    binding.tvLogContent.append("\n$line")
                    binding.logScrollView.post {
                        binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }

        binding.btnOpenLogs.setOnClickListener {
            AppLog.refreshFromLogcat()
            binding.tvLogContent.text = AppLog.getAllLogs()
            binding.logModalContainer.visibility = View.VISIBLE
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        binding.btnCloseLogs.setOnClickListener {
            binding.logModalContainer.visibility = View.GONE
        }

        binding.logModalContainer.setOnClickListener {
            binding.logModalContainer.visibility = View.GONE
        }

        binding.btnClearLogs.setOnClickListener {
            AppLog.clear()
            binding.tvLogContent.text = AppLog.getAllLogs()
        }

        binding.btnCopyLogs.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val all = AppLog.getAllLogs()
            cm.setPrimaryClip(ClipData.newPlainText("CamMonitor Logs", all))
            binding.tvCopyLabel.text = "COPIED!"
            Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    binding.tvCopyLabel.text = "COPY ALL"
                }
            }, 1500)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLog.i("Intent", "onNewIntent() action=${intent?.action}")

        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        usbCameraManager.onNewIntentAttachedDevice(device)
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("Lifecycle", "onResume() [state=${usbCameraManager.state}]")
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        running = true
        analysisHandler?.post(analysisLoop)

        // Only scan if not currently active or pending
        if (usbCameraManager.state == UsbCameraManager.UsbConnectionState.DISCONNECTED) {
            usbCameraManager.scanForConnectedDevices()
        }
    }

    override fun onPause() {
        super.onPause()
        AppLog.d("Lifecycle", "onPause() - keeping USB connection alive")
        running = false
        analysisHandler?.removeCallbacks(analysisLoop)
        sensorManager.unregisterListener(this)
        // CRITICAL: Do NOT disconnect or close the USB camera here.
        // On Android, requesting USB permission displays a dialog that triggers onPause().
        // Closing here would immediately destroy the stream upon permission confirmation.
    }

    override fun onDestroy() {
        AppLog.d("Lifecycle", "onDestroy()")
        AppLog.setListener(null)
        running = false
        analysisHandler?.removeCallbacksAndMessages(null)
        analysisThread?.quitSafely()
        analysisThread = null
        analysisHandler = null
        frameBmp?.recycle(); frameBmp = null
        maskBmp?.recycle();  maskBmp  = null
        mainHandler.removeCallbacksAndMessages(null)

        usbCameraManager.stop()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Window: full-screen, screen-always-on
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
    //  Splash screen helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun showSplash(message: String) {
        binding.statusText.text = message
        if (binding.splashLayout.visibility == View.VISIBLE) return
        binding.splashLayout.alpha = 0f
        binding.splashLayout.visibility = View.VISIBLE
        binding.splashLayout.animate().alpha(1f).setDuration(300).start()
    }

    private fun hideSplash() {
        if (binding.splashLayout.visibility != View.VISIBLE) return
        binding.splashLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.splashLayout.visibility = View.GONE
                }
            })
            .start()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Controls visibility toggle (smooth fade on tap)
    // ──────────────────────────────────────────────────────────────────────

    private fun toggleControls() {
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
    //  Accelerometer — phone tilt for the digital level line
    // ──────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(e: SensorEvent) {
        var a = Math.toDegrees(atan2(e.values[1].toDouble(), e.values[0].toDouble())).toFloat()
        if (a > 90f) a -= 180f else if (a < -90f) a += 180f
        val o = binding.overlay
        o.tiltDeg = o.tiltDeg * 0.8f + a * 0.2f  // low-pass for smooth line
        if (o.showLevel) o.invalidate()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    // ──────────────────────────────────────────────────────────────────────
    //  Background analysis loop (~7 fps — off the UI thread)
    // ──────────────────────────────────────────────────────────────────────

    private val analysisLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val o = binding.overlay
            if (o.showHist || o.showZebra || o.showPeak) analyzeFrame()
            mainHandler.post {
                if (!isFinishing && !isDestroyed) updateVideoRect()
            }
            analysisHandler?.postDelayed(this, 150)
        }
    }

    /** Keeps the overlay rect in sync with the TextureView's actual position. */
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
    //  Frame analysis: histogram + zebra stripes + focus peaking
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

        // Histogram — 64 luma buckets
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

        // Zebra + Focus peaking pixel mask
        if (o.showZebra || o.showPeak) {
            java.util.Arrays.fill(maskPx, 0)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    when {
                        o.showZebra && luma[i] >= zebraThreshold && ((x + y) and 7) < 4 ->
                            maskPx[i] = 0xCCFF2222.toInt()   // red diagonal stripes
                        o.showPeak -> {
                            val gx = abs(luma[i + 1] - luma[i - 1])
                            val gy = abs(luma[i + w] - luma[i - w])
                            if (gx + gy > peakThreshold) maskPx[i] = 0xFF00FF88.toInt()  // green
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
