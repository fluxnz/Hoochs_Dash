package com.example.hoochwindgrassodb2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class GaugeStyle(val title: String, val description: String) {
    PLAIN("Plain text", "Big numbers only. Simple and easy to read."),
    CLUSTER("Cluster", "Ticked dial with an inner ring and a marker that glides to the reading."),
    MINIMAL("Minimal", "A thin arc that eases smoothly to the value."),
    SEGMENTED("Segmented", "Lit blocks that step up and down, like a shift-light bar."),
    CLASSIC("Classic needle", "Round analogue face with a needle that swings past and settles.");

    companion object {
        fun fromName(name: String?): GaugeStyle = entries.firstOrNull { it.name == name } ?: PLAIN
    }
}

data class GaugePalette(
    val accent: Int,
    val track: Int,
    val text: Int,
    val muted: Int,
    val face: Int,
    val warning: Int
)

/** A dial that draws itself in one of the [GaugeStyle]s; each style has its own face, needle and motion. */
class GaugeView(context: Context, private val style: GaugeStyle) : View(context) {
    private var label = ""
    private var unit = ""
    private var minValue = 0.0
    private var maxValue = 100.0
    private var palette = GaugePalette(0, 0, 0, 0, 0, 0)
    private var valueText: String? = null
    private var shown = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    fun configure(label: String, unit: String, min: Double, max: Double, palette: GaugePalette) {
        this.label = label
        this.unit = unit
        this.minValue = min
        this.maxValue = if (max > min) max else min + 1
        this.palette = palette
        invalidate()
    }

    private var peakFraction: Float? = null

    /** Highest value seen this session, or null to hide the peak marker. */
    fun setPeak(value: Double?) {
        peakFraction = value?.let { ((it - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0).toFloat() }
        invalidate()
    }

    private fun drawPeak(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float, width: Float) {
        val fraction = peakFraction ?: return
        val angle = START + SWEEP * fraction
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.warning
        paint.strokeWidth = width
        canvas.drawLine(px(cx, inner, angle), py(cy, inner, angle), px(cx, outer, angle), py(cy, outer, angle), paint)
    }

    fun setReading(value: Double?, text: String?) {
        valueText = if (value == null) null else text
        val fraction = if (value == null) 0f else ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0).toFloat()
        contentDescription = "$label ${valueText ?: "no data"} $unit".trim()
        animateTo(fraction)
        invalidate()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (abs(target - shown) < 0.0005f) return
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = when (style) {
                GaugeStyle.CLASSIC -> 380L
                GaugeStyle.CLUSTER -> 250L
                GaugeStyle.MINIMAL -> 250L
                else -> 120L
            }
            interpolator = when (style) {
                GaugeStyle.CLASSIC -> OvershootInterpolator(1.2f)
                GaugeStyle.CLUSTER -> AccelerateDecelerateInterpolator()
                GaugeStyle.MINIMAL -> DecelerateInterpolator()
                else -> LinearInterpolator()
            }
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val fraction = shown.coerceIn(0f, 1f)
        when (style) {
            GaugeStyle.CLUSTER -> drawCluster(canvas, cx, cy, side, fraction)
            GaugeStyle.SEGMENTED -> drawSegmented(canvas, cx, cy, side, fraction)
            GaugeStyle.CLASSIC -> drawClassic(canvas, cx, cy, side)
            else -> drawMinimal(canvas, cx, cy, side, fraction)
        }
    }

    private fun drawMinimal(canvas: Canvas, cx: Float, cy: Float, side: Float, fraction: Float) {
        val stroke = side * 0.045f
        val r = side / 2 - stroke - side * 0.03f
        arc(canvas, cx, cy, r, START, SWEEP, stroke, palette.track, Paint.Cap.ROUND)
        if (fraction > 0f) arc(canvas, cx, cy, r, START, SWEEP * fraction, stroke, palette.accent, Paint.Cap.ROUND)
        drawPeak(canvas, cx, cy, r - stroke * 1.2f, r + stroke * 1.2f, stroke * 0.5f)
        readout(canvas, cx, cy, r)
        rangeLabels(canvas, cx, cy, r, stroke, side)
    }

    private fun drawCluster(canvas: Canvas, cx: Float, cy: Float, side: Float, fraction: Float) {
        val stroke = side * 0.075f
        val r = side / 2 - stroke - side * 0.03f
        arc(canvas, cx, cy, r, START, SWEEP, stroke, palette.track, Paint.Cap.ROUND)
        if (fraction > 0f) arc(canvas, cx, cy, r, START, SWEEP * fraction, stroke, palette.accent, Paint.Cap.ROUND)

        val tickOuter = r - stroke / 2 - side * 0.02f
        paint.style = Paint.Style.STROKE
        paint.color = palette.muted
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0..20) {
            val major = i % 5 == 0
            paint.strokeWidth = if (major) side * 0.012f else side * 0.007f
            val angle = START + SWEEP * i / 20f
            val length = if (major) side * 0.05f else side * 0.025f
            canvas.drawLine(
                px(cx, tickOuter, angle), py(cy, tickOuter, angle),
                px(cx, tickOuter - length, angle), py(cy, tickOuter - length, angle), paint
            )
        }
        paint.color = palette.track
        paint.strokeWidth = side * 0.006f
        canvas.drawCircle(cx, cy, tickOuter - side * 0.08f, paint)

        val angle = START + SWEEP * fraction
        paint.color = palette.text
        paint.strokeWidth = stroke * 0.32f
        canvas.drawLine(
            px(cx, r - stroke * 0.8f, angle), py(cy, r - stroke * 0.8f, angle),
            px(cx, r + stroke * 0.8f, angle), py(cy, r + stroke * 0.8f, angle), paint
        )
        drawPeak(canvas, cx, cy, r - stroke * 0.9f, r + stroke * 0.9f, stroke * 0.3f)
        readout(canvas, cx, cy, r * 0.9f)
        rangeLabels(canvas, cx, cy, r, stroke, side)
    }

    private fun drawSegmented(canvas: Canvas, cx: Float, cy: Float, side: Float, fraction: Float) {
        val stroke = side * 0.1f
        val r = side / 2 - stroke / 2 - side * 0.03f
        val lit = (fraction * SEGMENTS).roundToInt()
        val step = SWEEP / SEGMENTS
        for (i in 0 until SEGMENTS) {
            val color = when {
                i >= lit -> palette.track
                i == lit - 1 -> palette.text
                else -> palette.accent
            }
            arc(canvas, cx, cy, r, START + i * step + 0.9f, step - 1.8f, stroke, color, Paint.Cap.BUTT)
        }
        drawPeak(canvas, cx, cy, r - stroke * 0.8f, r + stroke * 0.8f, stroke * 0.2f)
        readout(canvas, cx, cy, r * 0.9f)
        rangeLabels(canvas, cx, cy, r, stroke, side)
    }

    private fun drawClassic(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val radius = side / 2 - side * 0.03f
        paint.style = Paint.Style.FILL
        paint.color = palette.face
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = palette.track
        paint.strokeWidth = radius * 0.05f
        canvas.drawCircle(cx, cy, radius, paint)

        val redZone = 0.85f
        arc(
            canvas, cx, cy, radius * 0.86f, START + SWEEP * redZone, SWEEP * (1 - redZone),
            radius * 0.05f, palette.warning, Paint.Cap.BUTT
        )

        val majors = niceDivisions()
        val minors = majors * 2
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0..minors) {
            val major = i % 2 == 0
            paint.color = if (major) palette.text else palette.muted
            paint.strokeWidth = radius * if (major) 0.03f else 0.018f
            val angle = START + SWEEP * i / minors
            val outer = radius * 0.93f
            val inner = outer - radius * if (major) 0.12f else 0.06f
            canvas.drawLine(px(cx, outer, angle), py(cy, outer, angle), px(cx, inner, angle), py(cy, inner, angle), paint)
        }
        for (i in 0..majors) {
            val angle = START + SWEEP * i / majors
            val value = minValue + (maxValue - minValue) * i / majors
            text(canvas, compact(value), px(cx, radius * 0.66f, angle), py(cy, radius * 0.66f, angle) + radius * 0.04f,
                radius * 0.11f, palette.muted, false, radius * 0.3f)
        }

        text(canvas, label.uppercase(), cx, cy - radius * 0.34f, radius * 0.12f, palette.muted, true, radius * 1.1f)
        val shownText = valueText ?: "—"
        text(canvas, shownText, cx, cy + radius * 0.58f, radius * 0.22f,
            if (valueText == null) palette.muted else palette.text, true, radius * 0.9f)
        if (unit.isNotEmpty()) text(canvas, unit, cx, cy + radius * 0.74f, radius * 0.11f, palette.accent, false, radius)

        drawPeak(canvas, cx, cy, radius * 0.74f, radius * 0.98f, radius * 0.04f)
        val angle = START + SWEEP * shown.coerceIn(-0.03f, 1.03f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.accent
        paint.strokeWidth = radius * 0.045f
        canvas.drawLine(
            px(cx, -radius * 0.12f, angle), py(cy, -radius * 0.12f, angle),
            px(cx, radius * 0.8f, angle), py(cy, radius * 0.8f, angle), paint
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.075f, paint)
        paint.color = palette.face
        canvas.drawCircle(cx, cy, radius * 0.03f, paint)
    }

    private fun readout(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val maxWidth = r * 1.3f
        text(canvas, label.uppercase(), cx, cy - r * 0.36f, r * 0.16f, palette.muted, true, maxWidth)
        text(canvas, valueText ?: "—", cx, cy + r * 0.22f, r * 0.5f,
            if (valueText == null) palette.muted else palette.text, true, maxWidth)
        if (unit.isNotEmpty()) text(canvas, unit, cx, cy + r * 0.5f, r * 0.17f, palette.accent, false, maxWidth)
    }

    private fun rangeLabels(canvas: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, side: Float) {
        val y = cy + r * 0.5f + stroke / 2 + side * 0.08f
        val dx = r * cos(Math.toRadians(30.0)).toFloat()
        text(canvas, compact(minValue), cx - dx, y, side * 0.07f, palette.muted, false, side * 0.3f)
        text(canvas, compact(maxValue), cx + dx, y, side * 0.07f, palette.muted, false, side * 0.3f)
    }

    private fun arc(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        start: Float, sweep: Float, stroke: Float, color: Int, cap: Paint.Cap
    ) {
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = cap
        paint.color = color
        canvas.drawArc(oval, start, sweep, false, paint)
    }

    private fun text(
        canvas: Canvas, value: String, x: Float, y: Float, size: Float,
        color: Int, bold: Boolean, maxWidth: Float
    ) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        paint.color = color
        paint.textSize = size
        val width = paint.measureText(value)
        if (width > maxWidth) paint.textSize = size * maxWidth / width
        canvas.drawText(value, x, y, paint)
    }

    private fun px(cx: Float, radius: Float, degrees: Float) = cx + radius * cos(Math.toRadians(degrees.toDouble())).toFloat()

    private fun py(cy: Float, radius: Float, degrees: Float) = cy + radius * sin(Math.toRadians(degrees.toDouble())).toFloat()

    // Chooses a tick count that gives whole-number steps (8000 -> 8 steps of 1000).
    private fun niceDivisions(): Int {
        val range = maxValue - minValue
        return listOf(8, 5, 6, 4, 10).firstOrNull { range / it == Math.rint(range / it) } ?: 5
    }

    private fun compact(value: Double): String = when {
        abs(value) >= 1000 && value % 1000.0 == 0.0 -> "${(value / 1000).toLong()}k"
        value == Math.rint(value) -> value.toLong().toString()
        else -> String.format(Locale.US, "%.1f", value)
    }

    private companion object {
        const val START = 150f
        const val SWEEP = 240f
        const val SEGMENTS = 24
    }
}
