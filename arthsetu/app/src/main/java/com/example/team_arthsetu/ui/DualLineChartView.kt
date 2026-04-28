package com.example.team_arthsetu.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Dual-line canvas chart for the Goals Planner.
 *   • AI Optimized  → filled gradient + solid blue line
 *   • Standard Path → dashed grey line (no fill)
 * Y-axis auto-scales; labels adapt to K/L/C units.
 */
class DualLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var aiValues  : List<Float> = emptyList()
    private var stdValues : List<Float> = emptyList()
    private var startYear : Int         = 2024

    // density once computed on first draw
    private var density   = 1f

    private fun dp(v: Float) = v * density

    // ── Paints (lazy init after density is known) ─────────────────────────────
    private val aiLinePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aiFillPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stdLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val yLabelPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val xLabelPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        aiLinePaint.apply {
            color = 0xFF4C35DC.toInt(); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        aiFillPaint.apply { style = Paint.Style.FILL }
        stdLinePaint.apply {
            color = 0xFFB0B8C8.toInt(); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        gridPaint.apply { color = 0x1A000000; strokeWidth = 1f; style = Paint.Style.STROKE }
        yLabelPaint.apply { color = 0xFF9CA3AF.toInt(); textAlign = Paint.Align.RIGHT }
        xLabelPaint.apply { color = 0xFF9CA3AF.toInt(); textAlign = Paint.Align.CENTER }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setData(ai: List<Float>, standard: List<Float>, yearStart: Int) {
        aiValues  = ai
        stdValues = standard
        startYear = yearStart
        invalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        density = resources.displayMetrics.density

        if (aiValues.size < 2 || width == 0 || height == 0) {
            drawPlaceholder(canvas)
            return
        }

        val pL = dp(52f)    // Y-axis label area
        val pR = dp(8f)
        val pT = dp(16f)
        val pB = dp(26f)    // X-axis label area

        val chartW = (width  - pL - pR).coerceAtLeast(1f)
        val chartH = (height - pT - pB).coerceAtLeast(1f)

        // Update paint sizes every draw (cheap, avoids stale density)
        aiLinePaint.strokeWidth  = dp(2.8f)
        stdLinePaint.strokeWidth = dp(2f)
        stdLinePaint.pathEffect  = DashPathEffect(floatArrayOf(dp(9f), dp(5f)), 0f)
        yLabelPaint.textSize     = dp(9f)
        xLabelPaint.textSize     = dp(9f)

        // Auto scale
        val n      = aiValues.size
        val allMax = (aiValues + stdValues).maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val stepVal = niceStep(allMax / 5f)
        val yMax   = stepVal * 6f    // 6 steps gives headroom above top value

        fun xOf(i: Int)   = pL + (i.toFloat() / (n - 1).coerceAtLeast(1)) * chartW
        fun yOf(v: Float) = pT + (1f - v.coerceIn(0f, yMax) / yMax) * chartH

        // ── Grid lines + Y labels ────────────────────────────────────────────
        for (s in 0..5) {
            val v = s * stepVal
            val y = yOf(v)
            canvas.drawLine(pL, y, pL + chartW, y, gridPaint)
            canvas.drawText(
                fmtRupee(v),
                pL - dp(4f),
                y + yLabelPaint.textSize / 3f,
                yLabelPaint
            )
        }

        // ── Standard path (dashed grey) ───────────────────────────────────────
        if (stdValues.size >= 2) {
            val xs = (0 until stdValues.size).map { xOf(it) }
            val ys = stdValues.map { yOf(it) }
            canvas.drawPath(bezier(xs, ys), stdLinePaint)
        }

        // ── AI path: fill + solid line ────────────────────────────────────────
        val aiXs = (0 until n).map { xOf(it) }
        val aiYs = aiValues.map { yOf(it) }
        val aiLine = bezier(aiXs, aiYs)

        val fill = Path(aiLine)
        fill.lineTo(aiXs.last(), pT + chartH)
        fill.lineTo(aiXs.first(), pT + chartH)
        fill.close()
        aiFillPaint.shader = LinearGradient(
            0f, pT, 0f, pT + chartH,
            intArrayOf(0x604C35DC.toInt(), 0x004C35DC.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, aiFillPaint)
        canvas.drawPath(aiLine, aiLinePaint)

        // ── X-axis year labels ────────────────────────────────────────────────
        val totalYears = n - 1
        val everyN     = if (totalYears <= 5) 1 else 2
        for (i in 0..totalYears step everyN) {
            canvas.drawText(
                "${startYear + i}",
                xOf(i),
                height.toFloat() - dp(4f),
                xLabelPaint
            )
        }
    }

    /** Shows a skeleton / hint when no data is set yet */
    private fun drawPlaceholder(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x14000000; strokeWidth = dp(1f); style = Paint.Style.STROKE
        }
        for (i in 1..4) {
            val y = height * i / 5f
            canvas.drawLine(dp(52f), y, width.toFloat() - dp(8f), y, p)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bezier(xs: List<Float>, ys: List<Float>): Path {
        val p = Path()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) {
            val cx = (xs[i - 1] + xs[i]) / 2f
            p.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i])
        }
        return p
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
        val n   = raw / mag
        return when {
            n <= 1f -> 1f
            n <= 2f -> 2f
            n <= 2.5f -> 2.5f
            n <= 5f -> 5f
            else    -> 10f
        } * mag
    }

    private fun fmtRupee(v: Float): String = when {
        v >= 1_00_00_000f -> "₹${(v / 1_00_00_000f).toInt()}C"
        v >= 1_00_000f    -> "₹${(v / 1_00_000f).toInt()}L"
        v >= 1_000f       -> "₹${(v / 1_000f).toInt()}K"
        else              -> "₹${v.toInt()}"
    }
}
