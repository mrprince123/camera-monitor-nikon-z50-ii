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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Transparent overlay that renders all five monitor overlays directly on Canvas:
 *
 *  1. Rule-of-thirds grid
 *  2. Digital level line (driven by accelerometer tilt)
 *  3. Luminance histogram
 *  4. Zebra stripes (over-exposure warning)
 *  5. Focus peaking (sharpness edge highlight)
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public state updated by MainActivity ──────────────────────────────
    @Volatile var showGrid  = false
    @Volatile var showLevel = false
    @Volatile var showHist  = false
    @Volatile var showZebra = false
    @Volatile var showPeak  = false

    /** Bounding rect of the live video within this view (maintained by MainActivity). */
    val videoRect = RectF()

    /** Pixel mask bitmap for zebra / focus peaking (updated off-UI thread, read here). */
    @Volatile var mask: Bitmap? = null

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

    /** Semi-transparent backdrop for the histogram. */
    private val histBgPaint = Paint().apply {
        color = Color.argb(160, 8, 10, 16)
    }

    /** Histogram bar gradient (filled in onDraw so it can track the view height). */
    private val histBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Faint border around the histogram box. */
    private val histBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 180, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    /** Scales the zebra/peaking mask bitmap over the video rect. */
    private val maskPaint = Paint().apply {
        isFilterBitmap = true
        alpha = 220
    }

    // ── Histogram gradient path (reused to avoid allocation in onDraw) ────
    private val histPath = Path()

    // ─────────────────────────────────────────────────────────────────────
    //  onDraw
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        val r = videoRect
        if (r.width() <= 0f || r.height() <= 0f) return

        // 1. Zebra / focus peaking pixel mask (drawn first so grid sits on top)
        if (showZebra || showPeak) {
            mask?.let { c.drawBitmap(it, null, r, maskPaint) }
        }

        // 2. Rule-of-thirds grid
        if (showGrid) drawGrid(c, r)

        // 3. Digital level line
        if (showLevel) drawLevel(c, r)

        // 4. Histogram
        if (showHist) drawHistogram(c, r)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Grid
    // ─────────────────────────────────────────────────────────────────────

    private fun drawGrid(c: Canvas, r: RectF) {
        for (i in 1..2) {
            val x = r.left + r.width()  * i / 3f
            val y = r.top  + r.height() * i / 3f
            c.drawLine(x, r.top,  x, r.bottom, gridPaint)
            c.drawLine(r.left, y, r.right, y,  gridPaint)
        }
        // Centre cross
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

        // Static reference line
        c.drawLine(cx - half, cy, cx + half, cy, levelRefPaint)

        // Rotating indicator
        levelPaint.color = if (abs(tiltDeg) < 1f) 0xFF00E676.toInt() else 0xFFFFD600.toInt()
        c.save()
        c.rotate(-tiltDeg, cx, cy)
        c.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        c.restore()

        // Pivot dot
        c.drawCircle(cx, cy, 6f, levelPaint)

        // Degree label above the centre
        val label = "%.1f°".format(tiltDeg)
        c.drawText(label, cx, cy - 28f, levelTextPaint)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Histogram
    // ─────────────────────────────────────────────────────────────────────

    private fun drawHistogram(c: Canvas, r: RectF) {
        val boxW = 260f
        val boxH = 120f
        val left = r.left + 20f
        val top  = r.bottom - boxH - 20f
        val right  = left + boxW
        val bottom = top + boxH

        // Background + border
        c.drawRoundRect(left, top, right, bottom, 8f, 8f, histBgPaint)
        c.drawRoundRect(left, top, right, bottom, 8f, 8f, histBorderPaint)

        // Bars with a gradient (shadow at bottom, bright at top)
        histBarPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFF00B4FF.toInt(), 0xFF005888.toInt()),
            null, Shader.TileMode.CLAMP
        )

        val localHist = hist
        val maxVal = max(1f, sqrt(localHist.max().toFloat()))
        val bw = (boxW - 4f) / localHist.size
        val innerTop  = top + 4f
        val innerBot  = bottom - 4f
        val innerH    = innerBot - innerTop

        histPath.reset()
        for (i in localHist.indices) {
            val bh = sqrt(localHist[i].toFloat()) / maxVal * innerH
            val bx = left + 2f + i * bw
            c.drawRect(bx, innerBot - bh, bx + bw - 0.5f, innerBot, histBarPaint)
        }
    }
}
