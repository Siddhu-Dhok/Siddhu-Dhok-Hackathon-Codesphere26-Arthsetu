package com.example.team_arthsetu.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Paired vertical bar chart: green bars = income, red bars = expense.
 * Each entry holds a short month label plus income & expense floats.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class MonthBar(val label: String, val income: Float, val expense: Float)

    private var bars: List<MonthBar> = emptyList()

    private val Float.dp
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)

    private val incomePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF22C55E.toInt()
    }
    private val expensePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEF4444.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF9CA3AF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = 0x12000000
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    fun setData(newBars: List<MonthBar>) {
        bars = newBars
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val pL  = 4f.dp
        val pR  = 4f.dp
        val pT  = 20f.dp   // space for value labels above
        val pB  = 22f.dp   // space for month labels below

        val chartW = width  - pL - pR
        val chartH = height - pT - pB
        val baseY  = pT + chartH

        val maxVal = bars.maxOf { maxOf(it.income, it.expense) }.coerceAtLeast(1f)

        labelPaint.textSize = 9f.dp
        valuePaint.textSize = 8f.dp

        val n      = bars.size
        val groupW = chartW / n
        val barW   = (groupW * 0.28f).coerceAtLeast(6f.dp)
        val gap    = 2f.dp

        // Horizontal baseline
        canvas.drawLine(pL, baseY, pL + chartW, baseY, gridPaint)

        bars.forEachIndexed { i, bar ->
            val gX = pL + i * groupW
            val cx = gX + groupW / 2f

            val incX = cx - barW - gap / 2f
            val expX = cx + gap / 2f

            val incH = (bar.income  / maxVal) * chartH
            val expH = (bar.expense / maxVal) * chartH

            val r = 3f.dp

            // Income bar
            if (incH > 0) {
                val rect = RectF(incX, baseY - incH, incX + barW, baseY)
                canvas.drawRoundRect(rect, r, r, incomePaint)
            }

            // Expense bar
            if (expH > 0) {
                val rect = RectF(expX, baseY - expH, expX + barW, baseY)
                canvas.drawRoundRect(rect, r, r, expensePaint)
            }

            // Value labels above bars
            if (incH > 0) {
                valuePaint.color = 0xFF16A34A.toInt()
                canvas.drawText(fmt(bar.income), incX + barW / 2f, baseY - incH - 4f.dp, valuePaint)
            }
            if (expH > 0) {
                valuePaint.color = 0xFFDC2626.toInt()
                canvas.drawText(fmt(bar.expense), expX + barW / 2f, baseY - expH - 4f.dp, valuePaint)
            }

            // Month label
            canvas.drawText(bar.label, cx, height.toFloat() - 4f.dp, labelPaint)
        }
    }

    private fun fmt(v: Float) = when {
        v >= 1_00_000 -> "${(v / 1_00_000).toInt()}L"
        v >= 1_000    -> "${(v / 1_000).toInt()}K"
        else          -> v.toInt().toString()
    }
}
