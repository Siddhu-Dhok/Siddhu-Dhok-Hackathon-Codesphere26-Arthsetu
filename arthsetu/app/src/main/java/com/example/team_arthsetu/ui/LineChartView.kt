package com.example.team_arthsetu.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Smooth gradient line chart rendered on Canvas.
 * Feed data via setData(); each Point carries a short month label and a float value.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Point(val label: String, val value: Float)

    private var points: List<Point> = emptyList()

    private val Float.dp
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0xFF4C35DC.toInt()
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    private val dotOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C35DC.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF9CA3AF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0x15000000
        strokeWidth = 1f
        style       = Paint.Style.STROKE
        pathEffect  = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(newPoints: List<Point>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        val pL = 8f.dp
        val pR = 8f.dp
        val pT = 12f.dp
        val pB = 28f.dp   // label area

        val w  = width  - pL - pR
        val h  = height - pT - pB

        val maxV = points.maxOf { it.value }.coerceAtLeast(1f)
        val minV = points.minOf { it.value }.coerceAtLeast(0f)
        val rng  = (maxV - minV).coerceAtLeast(1f)

        linePaint.strokeWidth = 2.5f.dp
        labelPaint.textSize   = 10f.dp

        val n  = points.size
        val xs = points.mapIndexed { i, _ -> pL + (i.toFloat() / (n - 1)) * w }
        val ys = points.map { p -> pT + (1f - (p.value - minV) / rng) * h }

        // Dashed grid lines
        for (i in 0..3) {
            val y = pT + (i / 3f) * h
            canvas.drawLine(pL, y, pL + w, y, gridPaint)
        }

        // Smooth bezier path helpers
        fun path(xArr: List<Float>, yArr: List<Float>): Path {
            val path = Path()
            path.moveTo(xArr[0], yArr[0])
            for (i in 1 until xArr.size) {
                val cx = (xArr[i - 1] + xArr[i]) / 2f
                path.cubicTo(cx, yArr[i - 1], cx, yArr[i], xArr[i], yArr[i])
            }
            return path
        }

        val linePath = path(xs, ys)

        // Gradient fill beneath the line
        val fillPath = Path(linePath)
        fillPath.lineTo(xs.last(), pT + h)
        fillPath.lineTo(xs.first(), pT + h)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, pT, 0f, pT + h,
            intArrayOf(0x384C35DC.toInt(), 0x004C35DC.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Line
        canvas.drawPath(linePath, linePaint)

        // Dots
        xs.zip(ys).forEach { (x, y) ->
            canvas.drawCircle(x, y, 5f.dp, dotOuterPaint)
            canvas.drawCircle(x, y, 3f.dp, dotInnerPaint)
        }

        // Month labels
        points.forEachIndexed { i, p ->
            canvas.drawText(p.label, xs[i], height.toFloat() - 4f.dp, labelPaint)
        }
    }
}
