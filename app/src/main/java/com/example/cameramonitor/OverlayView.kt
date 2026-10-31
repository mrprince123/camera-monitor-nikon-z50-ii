package com.example.cameramonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.example.cameramonitor.camera.AfTargetState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Transparent overlay that renders professional camera field monitoring tools:
 *  1. Rule-of-thirds grid
 *  2. Digital level line (driven by accelerometer tilt)
 *  3. Luminance histogram
 *  4. Zebra stripes (over-exposure warning)
 *  5. Focus peaking (sharpness edge highlight)
 *  6. False color exposure heatmap (IRE scale)
 *  7. Luma waveform oscilloscope monitor
 *  8. Aspect ratio frame guides (2.39:1 CinemaScope, 16:9, 4:3, 9:16, 1:1)
 *  9. Real-time Autofocus corner-bracket reticle
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class FrameGuide(val label: String, val ratio: Float) {
        NONE("OFF", 0f),
        RATIO_239_1("2.39:1", 2.39f),
        RATIO_16_9("16:9", 16f / 9f),
        RATIO_4_3("4:3", 4f / 3f),
        RATIO_9_16("9:16", 9f / 16f),
        RATIO_1_1("1:1", 1f)
    }

    // ── Public state updated by MainActivity ──────────────────────────────
    @Volatile var showGrid       = false
    @Volatile var showLevel      = false
    @Volatile var showHist       = false
    @Volatile var showZebra      = false
    @Volatile var showPeak       = false
    @Volatile var showFalseColor = false
    @Volatile var showWaveform   = false
    @Volatile var frameGuide     = FrameGuide.NONE

    /** Active Autofocus reticle state (drawn when camera is focusing or user touch-to-focus) */
    @Volatile var afTarget: AfTargetState? = null

    /** Bounding rect of the live video within this view (maintained by MainActivity). */
    val videoRect = RectF()

    /** Pixel mask bitmap for zebra / focus peaking. */
    @Volatile var mask: Bitmap? = null

    /** Pixel mask bitmap for False Color IRE heatmap. */
    @Volatile var falseColorMask: Bitmap? = null

    /** Waveform 2D density array: 128 horizontal columns x 64 vertical IRE bins. */
    @Volatile var waveformData: IntArray? = null

    /** Histogram bucket counts (64 bins across the luma range 0-255). */
    @Volatile var hist = IntArray(64)

    /** Current phone tilt in degrees (positive = clockwise). */
    @Volatile var tiltDeg = 0f

    // ── Paints ────────────────────────────────────────────────────────────

    /** Thin white lines for the rule-of-thirds grid. */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    /** White centre reference line for the level tool. */
    private val levelRefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    /** Coloured indicator line that rotates with the phone (green = level, yellow = tilted). */
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    /** Degree label beside the level line. */
    private val levelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    /** Semi-transparent backdrop for the histogram / waveform. */
    private val monitorBgPaint = Paint().apply {
        color = Color.argb(175, 8, 10, 16)
    }

    /** Histogram bar gradient. */
    private val histBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Faint yellow border around monitor boxes. */
    private val monitorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 196, 0)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    /** Scales mask bitmaps over the video rect. */
    private val maskPaint = Paint().apply {
        isFilterBitmap = true
        alpha = 230
    }

    /** Corner bracket reticle for Autofocus / Touch-to-focus box. */
    private val afBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    /** Letterbox matte paint for frame guides. */
    private val mattePaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** Guide lines for aspect ratio frames. */
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    /** Waveform trace paint. */
    private val waveTracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 245, 255)
        strokeWidth = 1.8f
    }

    /** Waveform reference grid lines and text. */
    private val waveGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
        textSize = 18f
        textAlign = Paint.Align.LEFT
    }

    private val histPath = Path()

    // ─────────────────────────────────────────────────────────────────────
    //  onDraw
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        val r = videoRect
        if (r.width() <= 0f || r.height() <= 0f) return

        // 1. False Color Exposure Heatmap (replaces preview rendering if active)
        if (showFalseColor) {
            falseColorMask?.let { c.drawBitmap(it, null, r, maskPaint) }
        }

        // 2. Zebra / focus peaking pixel mask
        if (!showFalseColor && (showZebra || showPeak)) {
            mask?.let { c.drawBitmap(it, null, r, maskPaint) }
        }

        // 3. Aspect Ratio Frame Guides (CinemaScope, 16:9, etc.)
        if (frameGuide != FrameGuide.NONE) {
            drawFrameGuides(c, r)
        }

        // 4. Rule-of-thirds grid
        if (showGrid) drawGrid(c, r)

        // 5. Digital level line
        if (showLevel) drawLevel(c, r)

        // 6. Histogram
        if (showHist) drawHistogram(c, r)

        // 7. Luma Waveform Monitor
        if (showWaveform) drawWaveform(c, r)

        // 8. Autofocus Reticle Box
        drawFocusBox(c, r)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Aspect Ratio Guides & Mattes
    // ─────────────────────────────────────────────────────────────────────

    private fun drawFrameGuides(c: Canvas, r: RectF) {
        val targetRatio = frameGuide.ratio
        if (targetRatio <= 0f) return

        val videoW = r.width()
        val videoH = r.height()
        val currentRatio = videoW / videoH

        val cropW: Float
        val cropH: Float

        if (targetRatio > currentRatio) {
            // Wider than video: pillarbox/letterbox top & bottom
            cropW = videoW
            cropH = videoW / targetRatio
        } else {
            // Taller than video: matte left & right
            cropH = videoH
            cropW = videoH * targetRatio
        }

        val cx = r.centerX()
        val cy = r.centerY()
        val left = cx - cropW / 2f
        val top = cy - cropH / 2f
        val right = cx + cropW / 2f
        val bottom = cy + cropH / 2f

        // Draw translucent mattes outside the cropped active frame
        if (top > r.top) {
            c.drawRect(r.left, r.top, r.right, top, mattePaint)
            c.drawRect(r.left, bottom, r.right, r.bottom, mattePaint)
        }
        if (left > r.left) {
            c.drawRect(r.left, top, left, bottom, mattePaint)
            c.drawRect(right, top, r.right, bottom, mattePaint)
        }

        // Draw active frame boundary box
        c.drawRect(left, top, right, bottom, guideLinePaint)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Grid
    // ─────────────────────────────────────────────────────────────────────

    private fun drawGrid(c: Canvas, r: RectF) {
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3f
            val y = r.top + r.height() * i / 3f
            c.drawLine(x, r.top, x, r.bottom, gridPaint)
            c.drawLine(r.left, y, r.right, y, gridPaint)
        }
        val cx = r.centerX(); val cy = r.centerY()
        val cs = 24f
        c.drawLine(cx - cs, cy, cx + cs, cy, gridPaint)
        c.drawLine(cx, cy - cs, cx, cy + cs, gridPaint)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Level
    // ─────────────────────────────────────────────────────────────────────

    private fun drawLevel(c: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val half = r.width() * 0.20f

        c.drawLine(cx - half, cy, cx + half, cy, levelRefPaint)

        val isLevel = abs(tiltDeg) < 1f
        levelPaint.color = if (isLevel) 0xFF00E676.toInt() else 0xFFFFC400.toInt()
        c.save()
        c.rotate(-tiltDeg, cx, cy)
        c.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        c.restore()

        c.drawCircle(cx, cy, 6f, levelPaint)

        val label = "%.1f°".format(tiltDeg)
        levelTextPaint.color = if (isLevel) 0xFF00E676.toInt() else 0xFFFFC400.toInt()
        c.drawText(label, cx, cy - 28f, levelTextPaint)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Histogram
    // ─────────────────────────────────────────────────────────────────────

    private fun drawHistogram(c: Canvas, r: RectF) {
        val boxW = 260f
        val boxH = 120f
        val left = r.left + 20f
        val top = r.bottom - boxH - 20f
        val right = left + boxW
        val bottom = top + boxH

        c.drawRoundRect(left, top, right, bottom, 14f, 14f, monitorBgPaint)
        c.drawRoundRect(left, top, right, bottom, 14f, 14f, monitorBorderPaint)

        histBarPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFFFFC400.toInt(), 0x55FFC400.toInt()),
            null, Shader.TileMode.CLAMP
        )

        val localHist = hist
        val maxVal = max(1f, sqrt(localHist.max().toFloat()))
        val bw = (boxW - 4f) / localHist.size
        val innerTop = top + 4f
        val innerBot = bottom - 4f
        val innerH = innerBot - innerTop

        histPath.reset()
        for (i in localHist.indices) {
            val bh = sqrt(localHist[i].toFloat()) / maxVal * innerH
            val bx = left + 2f + i * bw
            c.drawRect(bx, innerBot - bh, bx + bw - 0.5f, innerBot, histBarPaint)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Luma Waveform Monitor (Oscilloscope)
    // ─────────────────────────────────────────────────────────────────────

    private fun drawWaveform(c: Canvas, r: RectF) {
        val boxW = 320f
        val boxH = 160f
        // Position on bottom right (or left if histogram is on left)
        val left = r.right - boxW - 20f
        val top = r.bottom - boxH - 20f
        val right = left + boxW
        val bottom = top + boxH

        // Backdrop
        c.drawRoundRect(left, top, right, bottom, 14f, 14f, monitorBgPaint)
        c.drawRoundRect(left, top, right, bottom, 14f, 14f, monitorBorderPaint)

        // 100%, 50%, 0% IRE reference lines
        val innerT = top + 14f
        val innerB = bottom - 14f
        val innerH = innerB - innerT
        val innerL = left + 14f
        val innerR = right - 14f
        val innerW = innerR - innerL

        // 100 IRE
        c.drawLine(innerL, innerT, innerR, innerT, waveGridPaint)
        c.drawText("100", innerL + 4f, innerT + 14f, waveGridPaint)
        // 50 IRE
        val midY = innerT + innerH * 0.5f
        c.drawLine(innerL, midY, innerR, midY, waveGridPaint)
        c.drawText("50", innerL + 4f, midY - 4f, waveGridPaint)
        // 0 IRE
        c.drawLine(innerL, innerB, innerR, innerB, waveGridPaint)
        c.drawText("0", innerL + 4f, innerB - 4f, waveGridPaint)

        // Draw waveform trace points from 2D density array
        val data = waveformData ?: return
        val cols = 64
        val rows = 32
        if (data.size < cols * rows) return

        val dx = innerW / cols
        for (col in 0 until cols) {
            val px = innerL + col * dx
            for (row in 0 until rows) {
                val count = data[col * rows + row]
                if (count > 0) {
                    val py = innerB - (row.toFloat() / rows) * innerH
                    val alpha = minOf(255, 60 + count * 35)
                    waveTracePaint.alpha = alpha
                    c.drawPoint(px, py, waveTracePaint)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Autofocus Reticle Box
    // ─────────────────────────────────────────────────────────────────────

    private fun drawFocusBox(c: Canvas, r: RectF) {
        val target = afTarget ?: return
        val norm = target.rect
        val left = r.left + norm.left * r.width()
        val top = r.top + norm.top * r.height()
        val right = r.left + norm.right * r.width()
        val bottom = r.top + norm.bottom * r.height()

        afBoxPaint.color = when (target.status) {
            1 -> 0xFF00E676.toInt() // in focus: vibrant green
            2 -> 0xFFFF4D4D.toInt() // focus failed: red
            else -> 0xFFFFC400.toInt() // acquiring/active: bright yellow
        }

        val cornerLen = minOf(right - left, bottom - top) * 0.28f
        if (cornerLen > 4f) {
            // Top-left
            c.drawLine(left, top, left + cornerLen, top, afBoxPaint)
            c.drawLine(left, top, left, top + cornerLen, afBoxPaint)
            // Top-right
            c.drawLine(right, top, right - cornerLen, top, afBoxPaint)
            c.drawLine(right, top, right, top + cornerLen, afBoxPaint)
            // Bottom-left
            c.drawLine(left, bottom, left + cornerLen, bottom, afBoxPaint)
            c.drawLine(left, bottom, left, bottom - cornerLen, afBoxPaint)
            // Bottom-right
            c.drawLine(right, bottom, right - cornerLen, bottom, afBoxPaint)
            c.drawLine(right, bottom, right, bottom - cornerLen, afBoxPaint)

            // Center focus point
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            c.drawCircle(cx, cy, 3.5f, afBoxPaint)
        }
    }
}
