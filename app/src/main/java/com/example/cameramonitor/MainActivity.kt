package com.example.cameramonitor

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
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
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.cameramonitor.camera.CameraConnectionManager
import com.example.cameramonitor.camera.CameraDevice
import com.example.cameramonitor.camera.CameraPropertyType
import com.example.cameramonitor.camera.CameraPropertyValue
import com.example.cameramonitor.camera.CameraState
import com.example.cameramonitor.camera.usb.uvc.UvcCameraBackend
import com.example.cameramonitor.databinding.ActivityMainBinding
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import kotlinx.coroutines.launch
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
    private lateinit var cameraConnectionManager: CameraConnectionManager

    // ── Sensor ─────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager

    // ── Background analysis ────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var frameBmp: Bitmap? = null
    private var maskBmp: Bitmap? = null
    private var falseColorBmp: Bitmap? = null
    private var pixels = IntArray(0)
    private var luma = IntArray(0)
    private var maskPx = IntArray(0)
    private var falseColorPx = IntArray(0)
    private val waveData = IntArray(64 * 32)
    @Volatile private var running = false

    private val analysisWidth = 320
    private val zebraThreshold = 235   // luma 0-255; blown highlights
    private val peakThreshold = 40    // Sobel-like edge strength for peaking

    // ── Controls visibility ────────────────────────────────────────────────
    private var controlsVisible = true

    // ── Shutter & Video Mode ───────────────────────────────────────────────
    private var isVideoMode = false
    private var recSeconds = 0
    private val recTimerRunnable = object : Runnable {
        override fun run() {
            recSeconds++
            val m = recSeconds / 60
            val s = recSeconds % 60
            binding.tvRecTimer.text = "REC %02d:%02d".format(m, s)
            mainHandler.postDelayed(this, 1000)
        }
    }

    // ── Permissions ────────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        AppLog.i("Perm", "Permission result: RECORD_AUDIO=$audioGranted, CAMERA=$cameraGranted")
        if (audioGranted || cameraGranted) {
            cameraConnectionManager.scanForConnectedDevices()
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
            setAspectRatio(UvcCameraBackend.PREVIEW_WIDTH, UvcCameraBackend.PREVIEW_HEIGHT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    AppLog.i("UI", "SurfaceTexture available (${width}x${height})")
                    cameraConnectionManager.setPreviewSurface(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    AppLog.d("UI", "SurfaceTexture size changed (${width}x${height})")
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    AppLog.i("UI", "SurfaceTexture destroyed")
                    cameraConnectionManager.setPreviewSurface(null)
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

        // Wire touch-to-focus on camera view
        binding.cameraViewContainer.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val backend = cameraConnectionManager.activeBackend.value
                if (backend?.capabilities?.supportsTouchToFocus == true && v.width > 0 && v.height > 0) {
                    val normX = event.x / v.width
                    val normY = event.y / v.height
                    AppLog.i("UI", "Touch focus requested at ($normX, $normY)")
                    lifecycleScope.launch {
                        cameraConnectionManager.setTouchFocusPoint(PointF(normX, normY))
                    }
                }
            }
            false // allow parent click for controls toggle
        }

        // Wire Diagnostic HUD toggle
        binding.btnToggleHud.setOnClickListener {
            val isVis = binding.hudPanel.visibility == View.VISIBLE
            binding.hudPanel.visibility = if (isVis) View.GONE else View.VISIBLE
        }

        // Initialize Universal CameraConnectionManager
        cameraConnectionManager = CameraConnectionManager(this)

        lifecycleScope.launch {
            cameraConnectionManager.cameraState.collect { state ->
                handleCameraState(state)
            }
        }

        // Setup Nikon Remote Controls & Exposure UI
        setupCameraControls()
        setupExposureBar()
        observeCameraProperties()
        observeRecordingAndAfState()

        // Start dedicated background analysis thread
        analysisThread = HandlerThread("cam-analysis").also {
            it.start()
            analysisHandler = Handler(it.looper)
        }

        // Wire overlay toggle buttons with smooth feedback and yellow label highlights
        val o = binding.overlay
        binding.tgGrid.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgGrid)
            updateLabel(binding.lblGrid, v)
            o.showGrid = v
            o.invalidate()
        }
        binding.tgLevel.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgLevel)
            updateLabel(binding.lblLevel, v)
            o.showLevel = v
            o.invalidate()
        }
        binding.tgHist.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgHist)
            updateLabel(binding.lblHist, v)
            o.showHist = v
            o.invalidate()
        }
        binding.tgZebra.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgZebra)
            updateLabel(binding.lblZebra, v)
            o.showZebra = v
            o.invalidate()
        }
        binding.tgPeak.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgPeak)
            updateLabel(binding.lblPeak, v)
            o.showPeak = v
            o.invalidate()
        }
        binding.tgFalseColor.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgFalseColor)
            updateLabel(binding.lblFalseColor, v)
            o.showFalseColor = v
            o.invalidate()
        }
        binding.tgWaveform.setOnCheckedChangeListener { _, v ->
            animateButton(binding.tgWaveform)
            updateLabel(binding.lblWaveform, v)
            o.showWaveform = v
            o.invalidate()
        }
        binding.tgGuides.setOnClickListener {
            animateButton(binding.tgGuides)
            val nextGuide = when (o.frameGuide) {
                OverlayView.FrameGuide.NONE -> OverlayView.FrameGuide.RATIO_239_1
                OverlayView.FrameGuide.RATIO_239_1 -> OverlayView.FrameGuide.RATIO_16_9
                OverlayView.FrameGuide.RATIO_16_9 -> OverlayView.FrameGuide.RATIO_4_3
                OverlayView.FrameGuide.RATIO_4_3 -> OverlayView.FrameGuide.RATIO_9_16
                OverlayView.FrameGuide.RATIO_9_16 -> OverlayView.FrameGuide.RATIO_1_1
                OverlayView.FrameGuide.RATIO_1_1 -> OverlayView.FrameGuide.NONE
            }
            o.frameGuide = nextGuide
            binding.tgGuides.isChecked = (nextGuide != OverlayView.FrameGuide.NONE)
            binding.lblGuides.text = nextGuide.label
            updateLabel(binding.lblGuides, nextGuide != OverlayView.FrameGuide.NONE)
            o.invalidate()
        }

        // Tap anywhere on background → smoothly show / hide controls
        binding.root.setOnClickListener { toggleControls() }

        // Wire Diagnostic Logs UI & Media Gallery UI
        setupLogsUi()
        setupGalleryUi()

        // Apply initial orientation (Portrait or Landscape)
        applyOrientation(resources.configuration.orientation)

        // Initial splash
        showSplash("Connect your camera")

        // Start universal camera connection manager & register receiver
        cameraConnectionManager.start()

        // Ensure runtime permissions
        checkPermissions()

        // Check if device was launched via USB_DEVICE_ATTACHED intent
        checkInitialIntent(intent)
    }

    private fun handleCameraState(state: CameraState) {
        AppLog.d("UI", "CameraState update: $state")
        when (state) {
            is CameraState.Streaming -> {
                hideSplash()
                binding.statusBadgeText.text = "CONNECTED"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFFC400"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)

                binding.hudTvState.text = "State: STREAMING"
                val backend = cameraConnectionManager.activeBackend.value
                val backendName = when (backend?.backendType) {
                    CameraDevice.ConnectionType.USB_PTP -> "Nikon PTP (Full Control)"
                    CameraDevice.ConnectionType.HDMI_CAPTURE -> "HDMI Video Capture"
                    else -> "USB UVC Streaming"
                }
                binding.hudTvDevice.text = "Backend: $backendName"
                binding.hudTvResolution.text = "Resolution: ${state.resolution}"
                binding.hudTvFrames.text = "Frames: ${state.totalFrames} (${state.fps} fps)"
                binding.hudTvSurface.text = "Rendering: ${state.isRendering}"
                binding.hudTvError.visibility = View.GONE

                val hasControls = backend?.capabilities?.supportsRemoteShutter == true || backend?.capabilities?.supportsExposureSettings == true
                binding.shutterDock.visibility = if (hasControls && controlsVisible) View.VISIBLE else View.GONE
                binding.exposureBar.visibility = if (hasControls && controlsVisible) View.VISIBLE else View.GONE
            }
            is CameraState.Disconnected -> {
                binding.statusBadgeText.text = "DISCONNECTED"
                binding.statusBadgeText.setTextColor(Color.parseColor("#9AA2B3"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
                showSplash("Connect your camera\n\nCamera → USB-C → this phone")

                binding.hudTvState.text = "State: DISCONNECTED"
                binding.hudTvDevice.text = "Device: None"
                binding.hudTvFrames.text = "Frames: 0 (0 fps)"

                binding.shutterDock.visibility = View.GONE
                binding.exposureBar.visibility = View.GONE
                binding.recordingBanner.visibility = View.GONE
            }
            is CameraState.DeviceDetected -> {
                binding.statusBadgeText.text = "DETECTED"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFFC400"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                showSplash("${state.deviceName} Detected…")

                binding.hudTvState.text = "State: DETECTED"
                binding.hudTvDevice.text = "Device: ${state.deviceName} [${state.vidPid}]"

                binding.shutterDock.visibility = View.GONE
                binding.exposureBar.visibility = View.GONE
            }
            is CameraState.PermissionPending -> {
                binding.statusBadgeText.text = "PERMISSION"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFFC400"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                showSplash("Requesting USB Permission…\nTap 'OK' on system dialog")

                binding.hudTvState.text = "State: PERMISSION_PENDING"
            }
            is CameraState.Connecting -> {
                binding.statusBadgeText.text = "CONNECTING"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFFC400"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                showSplash(state.stage)

                binding.hudTvState.text = "State: CONNECTING (${state.stage})"
            }
            is CameraState.Connected -> {
                binding.statusBadgeText.text = "CONNECTED"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFFC400"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
                showSplash("Connected: ${state.device.displayName}…")

                binding.hudTvState.text = "State: CONNECTED"
                binding.hudTvDevice.text = "Device: ${state.device.displayName} [${state.device.connectionType}]"

                val hasControls = state.capabilities.supportsRemoteShutter || state.capabilities.supportsExposureSettings
                binding.shutterDock.visibility = if (hasControls && controlsVisible) View.VISIBLE else View.GONE
                binding.exposureBar.visibility = if (hasControls && controlsVisible) View.VISIBLE else View.GONE
            }
            is CameraState.Error -> {
                binding.statusBadgeText.text = "ERROR"
                binding.statusBadgeText.setTextColor(Color.parseColor("#FFFF4D4D"))
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
                showSplash(
                    "⚠ Camera connection error\n\n" +
                    "(${state.message})\n\n" +
                    "• Set camera USB to MTP/PTP or UVC\n" +
                    "• Reconnect USB-C data cable"
                )

                binding.hudTvState.text = "State: ERROR"
                binding.hudTvError.visibility = View.VISIBLE
                binding.hudTvError.text = "Error: ${state.message}"

                binding.shutterDock.visibility = View.GONE
                binding.exposureBar.visibility = View.GONE
                binding.recordingBanner.visibility = View.GONE
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Nikon PTP Remote Controls (Shutter, AF, Movie Record, Mode Switch)
    // ──────────────────────────────────────────────────────────────────────

    private fun setupCameraControls() {
        // 1. Shutter / Record Trigger Button
        binding.btnShutter.setOnClickListener {
            animateButton(binding.btnShutter)
            val backend = cameraConnectionManager.activeBackend.value ?: return@setOnClickListener

            if (isVideoMode) {
                if (cameraConnectionManager.isMovieRecording.value) {
                    AppLog.i("UI", "Stopping internal movie recording...")
                    lifecycleScope.launch {
                        val res = cameraConnectionManager.stopMovieRecording()
                        if (res.isFailure) Toast.makeText(this@MainActivity, "Stop rec error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppLog.i("UI", "Starting internal movie recording...")
                    lifecycleScope.launch {
                        val res = cameraConnectionManager.startMovieRecording()
                        if (res.isFailure) Toast.makeText(this@MainActivity, "Start rec error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                AppLog.i("UI", "Triggering remote shutter capture...")
                binding.cameraViewContainer.animate().alpha(0.2f).setDuration(60).withEndAction {
                    binding.cameraViewContainer.animate().alpha(1.0f).setDuration(120).start()
                }.start()

                lifecycleScope.launch {
                    val res = cameraConnectionManager.triggerShutter()
                    if (res.isSuccess) {
                        Toast.makeText(this@MainActivity, "Photo Captured!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Shutter: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 2. Mode Toggle (Photo vs Video)
        binding.btnModeToggle.setOnClickListener {
            animateButton(binding.btnModeToggle)
            isVideoMode = !isVideoMode
            AppLog.i("UI", "Switched camera mode: isVideoMode=$isVideoMode")
            if (isVideoMode) {
                binding.ivModeIcon.setImageResource(R.drawable.ic_camera_video)
                binding.btnShutter.setBackgroundResource(R.drawable.btn_shutter_record)
            } else {
                binding.ivModeIcon.setImageResource(R.drawable.ic_camera_photo)
                binding.btnShutter.setBackgroundResource(R.drawable.btn_shutter_photo)
            }
        }

        // 3. AF-ON Button (Autofocus trigger)
        binding.btnAfOn.setOnClickListener {
            animateButton(binding.btnAfOn)
            AppLog.i("UI", "Triggering AF-ON...")
            lifecycleScope.launch {
                cameraConnectionManager.triggerAutofocus()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Exposure Bar & Quick Setting Modal (ISO, Shutter, Aperture, EV, WB)
    // ──────────────────────────────────────────────────────────────────────

    private fun setupExposureBar() {
        binding.btnIso.setOnClickListener { openSettingSelector(CameraPropertyType.ISO) }
        binding.btnShutterSpeed.setOnClickListener { openSettingSelector(CameraPropertyType.SHUTTER_SPEED) }
        binding.btnAperture.setOnClickListener { openSettingSelector(CameraPropertyType.APERTURE) }
        binding.btnEv.setOnClickListener { openSettingSelector(CameraPropertyType.EXPOSURE_COMPENSATION) }
        binding.btnWb.setOnClickListener { openSettingSelector(CameraPropertyType.WHITE_BALANCE) }

        binding.btnCloseSettingModal.setOnClickListener {
            binding.settingModalContainer.visibility = View.GONE
        }
        binding.settingModalContainer.setOnClickListener {
            binding.settingModalContainer.visibility = View.GONE
        }
    }

    private fun openSettingSelector(type: CameraPropertyType) {
        val title = when (type) {
            CameraPropertyType.ISO -> "ISO SENSITIVITY"
            CameraPropertyType.SHUTTER_SPEED -> "SHUTTER SPEED"
            CameraPropertyType.APERTURE -> "LENS APERTURE"
            CameraPropertyType.EXPOSURE_COMPENSATION -> "EXPOSURE COMPENSATION"
            CameraPropertyType.WHITE_BALANCE -> "WHITE BALANCE"
            else -> type.name
        }
        binding.tvSettingModalTitle.text = title

        val presets = when (type) {
            CameraPropertyType.ISO -> CameraPropertyValue.ISO_PRESETS
            CameraPropertyType.SHUTTER_SPEED -> CameraPropertyValue.SHUTTER_PRESETS
            CameraPropertyType.APERTURE -> CameraPropertyValue.APERTURE_PRESETS
            CameraPropertyType.EXPOSURE_COMPENSATION -> CameraPropertyValue.EV_PRESETS
            CameraPropertyType.WHITE_BALANCE -> CameraPropertyValue.WB_PRESETS
            else -> emptyList()
        }

        binding.settingOptionsContainer.removeAllViews()
        val currentVal = cameraConnectionManager.properties.value[type]?.displayValue ?: ""
        val density = resources.displayMetrics.density

        for (preset in presets) {
            val chip = TextView(this).apply {
                text = preset
                val isSelected = (preset == currentVal)
                setTextColor(if (isSelected) Color.parseColor("#FF0A0B0E") else Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(if (isSelected) R.drawable.bg_action_btn else R.drawable.bg_log_pill)
                val pSide = (16 * density).toInt()
                val pTop = (8 * density).toInt()
                setPadding(pSide, pTop, pSide, pTop)

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = lp

                setOnClickListener {
                    val raw = when (type) {
                        CameraPropertyType.ISO -> preset.toIntOrNull() ?: 400
                        CameraPropertyType.APERTURE -> ((preset.removePrefix("f/").toFloatOrNull() ?: 2.8f) * 100).toInt()
                        else -> preset.hashCode()
                    }
                    val propVal = CameraPropertyValue(type, raw, preset)
                    lifecycleScope.launch {
                        cameraConnectionManager.setProperty(type, propVal)
                    }
                    binding.settingModalContainer.visibility = View.GONE
                }
            }
            binding.settingOptionsContainer.addView(chip)
        }

        binding.settingModalContainer.visibility = View.VISIBLE
    }

    private fun observeCameraProperties() {
        lifecycleScope.launch {
            cameraConnectionManager.properties.collect { props ->
                props[CameraPropertyType.ISO]?.let { binding.tvIsoValue.text = it.displayValue }
                props[CameraPropertyType.SHUTTER_SPEED]?.let { binding.tvShutterSpeedValue.text = it.displayValue }
                props[CameraPropertyType.APERTURE]?.let { binding.tvApertureValue.text = it.displayValue }
                props[CameraPropertyType.EXPOSURE_COMPENSATION]?.let { binding.tvEvValue.text = it.displayValue }
                props[CameraPropertyType.WHITE_BALANCE]?.let { binding.tvWbValue.text = it.displayValue }
                props[CameraPropertyType.BATTERY_LEVEL]?.let { binding.tvBatteryValue.text = "🔋 ${it.displayValue}" }
            }
        }
    }

    private fun observeRecordingAndAfState() {
        lifecycleScope.launch {
            cameraConnectionManager.isMovieRecording.collect { recording ->
                if (recording) {
                    binding.recordingBanner.visibility = View.VISIBLE
                    binding.btnShutter.setBackgroundResource(R.drawable.btn_shutter_recording)
                    recSeconds = 0
                    binding.tvRecTimer.text = "REC 00:00"
                    mainHandler.postDelayed(recTimerRunnable, 1000)
                } else {
                    binding.recordingBanner.visibility = View.GONE
                    mainHandler.removeCallbacks(recTimerRunnable)
                    if (isVideoMode) {
                        binding.btnShutter.setBackgroundResource(R.drawable.btn_shutter_record)
                    } else {
                        binding.btnShutter.setBackgroundResource(R.drawable.btn_shutter_photo)
                    }
                }
            }
        }

        lifecycleScope.launch {
            cameraConnectionManager.afTargetState.collect { target ->
                binding.overlay.afTarget = target
                binding.overlay.invalidate()
            }
        }
    }
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
            cameraConnectionManager.onNewIntentAttachedDevice(device)
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

    private fun setupGalleryUi() {
        binding.btnOpenGallery.setOnClickListener {
            openGallery()
        }
        binding.btnCloseGallery.setOnClickListener {
            binding.galleryModalContainer.visibility = View.GONE
        }
        binding.galleryModalContainer.setOnClickListener {
            binding.galleryModalContainer.visibility = View.GONE
        }
        binding.btnRefreshGallery.setOnClickListener {
            openGallery()
        }
    }

    private fun openGallery() {
        val backend = cameraConnectionManager.activeBackend.value
        binding.galleryModalContainer.visibility = View.VISIBLE
        binding.tvGalleryStatus.visibility = View.VISIBLE
        binding.tvGalleryStatus.text = "Querying Nikon SD card storage..."
        binding.galleryItemsContainer.removeAllViews()

        lifecycleScope.launch {
            if (backend is PtpUsbCameraBackend) {
                val ptpConnField = try {
                    val field = PtpUsbCameraBackend::class.java.getDeclaredField("ptpConnection")
                    field.isAccessible = true
                    field.get(backend) as? com.example.cameramonitor.camera.usb.ptp.PtpUsbConnection
                } catch (_: Exception) { null }

                if (ptpConnField != null && ptpConnField.isConnected) {
                    val mediaManager = com.example.cameramonitor.camera.usb.ptp.PtpMediaManager(this@MainActivity)
                    val items = mediaManager.getMediaList(ptpConnField)
                    binding.tvGalleryStatus.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    if (items.isEmpty()) {
                        binding.tvGalleryStatus.text = "No photos or videos found on camera SD card."
                    } else {
                        val density = resources.displayMetrics.density
                        for (item in items) {
                            val card = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setBackgroundResource(R.drawable.bg_log_pill)
                                val p = (10 * density).toInt()
                                setPadding(p, p, p, p)
                                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                lp.bottomMargin = (8 * density).toInt()
                                layoutParams = lp

                                val thumbIv = ImageView(this@MainActivity).apply {
                                    val size = (64 * density).toInt()
                                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (12 * density).toInt() }
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setBackgroundColor(Color.parseColor("#33FFFFFF"))
                                }
                                addView(thumbIv)

                                val infoLayout = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                                    val nameTv = TextView(this@MainActivity).apply {
                                        text = item.filename
                                        setTextColor(Color.WHITE)
                                        textSize = 14f
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                    addView(nameTv)

                                    val sizeMb = "%.1f MB".format(item.sizeBytes / (1024f * 1024f))
                                    val typeStr = if (item.isVideo) "Video" else if (item.isRaw) "RAW NEF" else "JPEG Photo"
                                    val detailTv = TextView(this@MainActivity).apply {
                                        text = "$typeStr • $sizeMb"
                                        setTextColor(Color.parseColor("#AAFFFFFF"))
                                        textSize = 11f
                                        setPadding(0, 4, 0, 0)
                                    }
                                    addView(detailTv)
                                }
                                addView(infoLayout)

                                val downloadBtn = TextView(this@MainActivity).apply {
                                    text = "SAVE"
                                    setTextColor(Color.parseColor("#FF0A0B0E"))
                                    textSize = 11f
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setBackgroundResource(R.drawable.bg_action_btn)
                                    val hPad = (14 * density).toInt()
                                    val vPad = (8 * density).toInt()
                                    setPadding(hPad, vPad, hPad, vPad)
                                    setOnClickListener {
                                        text = "SAVING…"
                                        lifecycleScope.launch {
                                            val dlRes = mediaManager.downloadOriginal(ptpConnField, item)
                                            if (dlRes.isSuccess) {
                                                text = "SAVED ✓"
                                                Toast.makeText(this@MainActivity, "Saved ${item.filename} to Pictures/CamMonitor!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                text = "FAILED"
                                                Toast.makeText(this@MainActivity, "Download failed: ${dlRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                                addView(downloadBtn)

                                lifecycleScope.launch {
                                    val thumb = mediaManager.getThumbnail(ptpConnField, item.handle)
                                    if (thumb != null) {
                                        thumbIv.setImageBitmap(thumb)
                                    }
                                }
                            }
                            binding.galleryItemsContainer.addView(card)
                        }
                    }
                } else {
                    binding.tvGalleryStatus.text = "Camera SD card browsing requires active Nikon PTP tethered connection."
                }
            } else {
                binding.tvGalleryStatus.text = "SD Card media transfer is available when camera is connected in MTP/PTP mode."
            }
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
        cameraConnectionManager.onNewIntentAttachedDevice(device)
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("Lifecycle", "onResume() [state=${cameraConnectionManager.cameraState.value}]")
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        running = true
        analysisHandler?.post(analysisLoop)

        // Check if permission was granted or device state changed while paused
        cameraConnectionManager.onResumeCheck()
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
        falseColorBmp?.recycle(); falseColorBmp = null
        mainHandler.removeCallbacksAndMessages(null)

        cameraConnectionManager.stop()
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
            .setDuration(220)
            .withEndAction {
                binding.controls.visibility =
                    if (controlsVisible) View.VISIBLE else View.INVISIBLE
            }
            .start()

        binding.topBar.animate()
            .alpha(target)
            .setDuration(220)
            .withEndAction {
                binding.topBar.visibility =
                    if (controlsVisible) View.VISIBLE else View.INVISIBLE
            }
            .start()

        val backend = cameraConnectionManager.activeBackend.value
        val hasControls = backend?.capabilities?.supportsRemoteShutter == true || backend?.capabilities?.supportsExposureSettings == true
        if (hasControls) {
            binding.shutterDock.animate().alpha(target).setDuration(220).withEndAction {
                binding.shutterDock.visibility = if (controlsVisible) View.VISIBLE else View.INVISIBLE
            }.start()
            binding.exposureBar.animate().alpha(target).setDuration(220).withEndAction {
                binding.exposureBar.visibility = if (controlsVisible) View.VISIBLE else View.INVISIBLE
            }.start()
        }
    }

    private fun animateButton(view: View) {
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(130)
                    .start()
            }
            .start()
    }

    private fun updateLabel(label: TextView, isChecked: Boolean) {
        label.setTextColor(if (isChecked) Color.parseColor("#FFFFC400") else Color.parseColor("#99FFFFFF"))
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Orientation switching (Portrait vs Landscape without stream restart)
    // ──────────────────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.i("UI", "onConfigurationChanged() orientation=${newConfig.orientation}")
        applyOrientation(newConfig.orientation)
    }

    private fun applyOrientation(orientation: Int) {
        val isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE)
        AppLog.i("UI", "applyOrientation: isLandscape=$isLandscape")

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        // 1. Camera View Container
        val camParams = binding.cameraViewContainer.layoutParams as FrameLayout.LayoutParams
        if (isLandscape) {
            camParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            camParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            camParams.gravity = Gravity.CENTER
            camParams.topMargin = 0
        } else {
            camParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            camParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            camParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            camParams.topMargin = dp(56) // Below status top bar
        }
        binding.cameraViewContainer.layoutParams = camParams

        // 2. Shutter & Recording Dock (ZineControl style)
        val shutterParams = binding.shutterDock.layoutParams as FrameLayout.LayoutParams
        if (isLandscape) {
            binding.shutterDock.orientation = LinearLayout.VERTICAL
            shutterParams.gravity = Gravity.END or Gravity.BOTTOM
            shutterParams.marginEnd = dp(20)
            shutterParams.bottomMargin = dp(20)
        } else {
            binding.shutterDock.orientation = LinearLayout.HORIZONTAL
            shutterParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            shutterParams.marginEnd = 0
            shutterParams.bottomMargin = dp(20)

            // Adjust button margins inside shutter dock for horizontal row
            val btnShutterParams = binding.btnShutter.layoutParams as LinearLayout.LayoutParams
            btnShutterParams.marginStart = dp(16)
            btnShutterParams.marginEnd = dp(16)
            btnShutterParams.bottomMargin = 0
            binding.btnShutter.layoutParams = btnShutterParams

            val btnAfParams = binding.btnAfOn.layoutParams as LinearLayout.LayoutParams
            btnAfParams.bottomMargin = 0
            binding.btnAfOn.layoutParams = btnAfParams
        }
        binding.shutterDock.layoutParams = shutterParams

        // 3. Control Bar Container (ZineControl style)
        val controlsParams = binding.controls.layoutParams as FrameLayout.LayoutParams
        if (isLandscape) {
            binding.controls.orientation = LinearLayout.VERTICAL
            controlsParams.width = FrameLayout.LayoutParams.WRAP_CONTENT
            controlsParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            controlsParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            controlsParams.marginEnd = dp(16)
            controlsParams.bottomMargin = dp(90) // Stacked above shutter dock
            binding.controls.setPadding(dp(8), dp(12), dp(8), dp(12))

            // Button margins inside controls
            val btnItems = listOf(
                binding.itemGrid, binding.itemLevel, binding.itemHist,
                binding.itemZebra, binding.itemPeak, binding.itemFalseColor,
                binding.itemWaveform
            )
            for (item in btnItems) {
                val p = item.layoutParams as LinearLayout.LayoutParams
                p.marginEnd = 0
                p.bottomMargin = dp(10)
                item.layoutParams = p
            }
            val lastP = binding.itemGuides.layoutParams as LinearLayout.LayoutParams
            lastP.marginEnd = 0
            lastP.bottomMargin = 0
            binding.itemGuides.layoutParams = lastP
        } else {
            binding.controls.orientation = LinearLayout.HORIZONTAL
            controlsParams.width = FrameLayout.LayoutParams.WRAP_CONTENT
            controlsParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            controlsParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            controlsParams.marginEnd = 0
            controlsParams.bottomMargin = dp(104) // Placed right above horizontal shutter dock
            binding.controls.setPadding(dp(14), dp(8), dp(14), dp(8))

            // Button margins inside controls
            val btnItems = listOf(
                binding.itemGrid, binding.itemLevel, binding.itemHist,
                binding.itemZebra, binding.itemPeak, binding.itemFalseColor,
                binding.itemWaveform
            )
            for (item in btnItems) {
                val p = item.layoutParams as LinearLayout.LayoutParams
                p.marginEnd = dp(14)
                p.bottomMargin = 0
                item.layoutParams = p
            }
            val lastP = binding.itemGuides.layoutParams as LinearLayout.LayoutParams
            lastP.marginEnd = 0
            lastP.bottomMargin = 0
            binding.itemGuides.layoutParams = lastP
        }
        binding.controls.layoutParams = controlsParams

        // 4. Update overlay rect to match new dimensions
        binding.root.post {
            updateVideoRect()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Accelerometer — phone tilt for the digital level line
    // ──────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(e: SensorEvent) {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val x = e.values[0].toDouble()
        val y = e.values[1].toDouble()

        var a = when (rotation) {
            Surface.ROTATION_90 -> Math.toDegrees(atan2(y, x)).toFloat()
            Surface.ROTATION_270 -> -Math.toDegrees(atan2(y, x)).toFloat()
            Surface.ROTATION_180 -> -Math.toDegrees(atan2(x, y)).toFloat()
            else -> Math.toDegrees(atan2(x, y)).toFloat()
        }

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
            if (o.showHist || o.showZebra || o.showPeak || o.showFalseColor || o.showWaveform) {
                analyzeFrame()
            }
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
    //  Frame analysis: histogram + zebra + peaking + false color + waveform
    // ──────────────────────────────────────────────────────────────────────

    private fun analyzeFrame() {
        val state = cameraConnectionManager.cameraState.value
        if (state !is CameraState.Streaming) {
            return
        }
        val tv = textureView ?: return
        if (!tv.isAvailable || tv.width == 0 || tv.height == 0) return

        val w = analysisWidth
        val h = (w.toFloat() * tv.height / tv.width).toInt().coerceAtLeast(2)

        if (frameBmp?.width != w || frameBmp?.height != h) {
            frameBmp?.recycle()
            maskBmp?.recycle()
            falseColorBmp?.recycle()
            frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            maskBmp  = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            falseColorBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels   = IntArray(w * h)
            luma     = IntArray(w * h)
            maskPx   = IntArray(w * h)
            falseColorPx = IntArray(w * h)
        }

        val bmp = frameBmp ?: return
        try { tv.getBitmap(bmp) } catch (_: Exception) { return }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val o = binding.overlay

        // 1. Histogram — 64 luma buckets
        val hist = IntArray(64)
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (0.299f * Color.red(p) + 0.587f * Color.green(p) +
                     0.114f * Color.blue(p)).toInt()
            luma[i] = y
            hist[y shr 2]++
        }
        if (o.showHist) {
            mainHandler.post {
                if (!isFinishing && !isDestroyed) { o.hist = hist; o.invalidate() }
            }
        }

        // 2. False Color IRE Exposure Heatmap
        if (o.showFalseColor) {
            val fcBmp = falseColorBmp
            if (fcBmp != null) {
                for (i in pixels.indices) {
                    falseColorPx[i] = FALSE_COLOR_LUT[luma[i].coerceIn(0, 255)]
                }
                fcBmp.setPixels(falseColorPx, 0, w, 0, 0, w, h)
                val snapshot = fcBmp.copy(Bitmap.Config.ARGB_8888, false)
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        o.falseColorMask = snapshot
                        o.invalidate()
                    }
                }
            }
        }

        // 3. Luma Waveform Monitor (Oscilloscope trace)
        if (o.showWaveform) {
            java.util.Arrays.fill(waveData, 0)
            val cols = 64
            val rows = 32
            val colStep = (w / cols).coerceAtLeast(1)
            val rowStep = (h / rows).coerceAtLeast(1)
            for (y in 0 until h step rowStep) {
                val rowOffset = y * w
                for (x in 0 until w step colStep) {
                    val col = (x * cols / w).coerceIn(0, cols - 1)
                    val lumVal = luma[rowOffset + x]
                    val row = (lumVal * (rows - 1) / 255).coerceIn(0, rows - 1)
                    val idx = col * rows + row
                    if (idx in waveData.indices) {
                        waveData[idx]++
                    }
                }
            }
            val waveSnapshot = waveData.clone()
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    o.waveformData = waveSnapshot
                    o.invalidate()
                }
            }
        }

        // 4. Zebra + Focus peaking pixel mask
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

    companion object {
        /**
         * False Color IRE scale lookup table (0-255 luma -> ARGB color):
         * 0-10 IRE: Deep Purple (underexposed/clipped shadows)
         * 11-38 IRE: Navy Blue (shadows)
         * 39-48 IRE: Green (18% Middle Grey reference)
         * 49-62 IRE: Pink (skin tone reference)
         * 63-75 IRE: Mid-high Grey
         * 76-88 IRE: Yellow (near highlight)
         * 89-98 IRE: Orange (highlight warning)
         * 99-100 IRE: Vibrant Red (blown/clipped highlights)
         */
        private val FALSE_COLOR_LUT = IntArray(256) { y ->
            when (y) {
                in 0..25 -> 0xFF7A00B8.toInt()
                in 26..97 -> 0xFF0033AA.toInt()
                in 98..122 -> 0xFF00CC44.toInt()
                in 123..158 -> 0xFFFF70A6.toInt()
                in 159..191 -> 0xFF777777.toInt()
                in 192..224 -> 0xFFFFC400.toInt()
                in 225..250 -> 0xFFFF5722.toInt()
                else -> 0xFFFF1744.toInt()
            }
        }
    }
}
