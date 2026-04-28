package com.example.team_arthsetu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A lightweight donut (ring) chart drawn entirely on Canvas.
 * No external library required.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val label: String, val percent: Float, val color: Int)

    private var slices: List<Slice> = emptyList()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val oval = RectF()

    // Gap (in degrees) between slices for a clean look
    private val GAP_DEG = 2f

    fun setSlices(newSlices: List<Slice>) {
        slices = newSlices.filter { it.percent > 0f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.isEmpty()) return

        val cx = width  / 2f
        val cy = height / 2f
        val radius    = minOf(cx, cy) * 0.80f
        val strokeW   = radius * 0.44f           // ring thickness = 44% of radius

        arcPaint.strokeWidth = strokeW
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.sumOf { it.percent.toDouble() }.toFloat().coerceAtLeast(1f)

        var startAngle = -90f                    // start from the top
        for (slice in slices) {
            val sweep = (slice.percent / total) * 360f - GAP_DEG
            arcPaint.color = slice.color
            canvas.drawArc(oval, startAngle, sweep.coerceAtLeast(0.5f), false, arcPaint)
            startAngle += sweep + GAP_DEG
        }
    }
}
