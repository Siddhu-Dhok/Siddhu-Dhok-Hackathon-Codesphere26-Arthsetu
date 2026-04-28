package com.example.team_arthsetu.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.team_arthsetu.model.MonthPoint

/**
 * Custom chart for the Financial Risk Simulator.
 * • Draws balance-over-time as a smooth bezier line
 * • Positive balance area: green gradient fill
 * • Negative balance area: red gradient fill
 * • Bold dashed vertical line at the depletion month
 * • Horizontal zero-line for reference
 */
class SimChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var points         : List<MonthPoint> = emptyList()
    private var depletionMonth : Int              = -1    // -1 = no depletion

    private var density = 1f
    private fun dp(v: Float) = v * density

    // ── Paints ────────────────────────────────────────────────────────────────
    private val linePaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPosPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillNegPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroLinePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depletPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depletDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val yLabelPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val xLabelPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val survivalPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        linePaint.apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fillPosPaint.apply { style = Paint.Style.FILL }
        fillNegPaint.apply { style = Paint.Style.FILL }
        zeroLinePaint.apply { color = 0xFF9CA3AF.toInt(); style = Paint.Style.STROKE }
        depletPaint.apply { color = 0xFFEF4444.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        depletDotPaint.apply { color = 0xFFEF4444.toInt(); style = Paint.Style.FILL }
        gridPaint.apply { color = 0x0F000000; style = Paint.Style.STROKE }
        yLabelPaint.apply { color = 0xFF9CA3AF.toInt(); textAlign = Paint.Align.RIGHT }
        xLabelPaint.apply { color = 0xFF9CA3AF.toInt(); textAlign = Paint.Align.CENTER }
        survivalPaint.apply { color = 0xFF22C55E.toInt(); style = Paint.Style.FILL; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    }

    // ── API ───────────────────────────────────────────────────────────────────

    fun setData(pts: List<MonthPoint>, depletion: Int) {
        points         = pts
        depletionMonth = depletion
        invalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        density = resources.displayMetrics.density

        if (points.size < 2 || width == 0 || height == 0) {
            drawPlaceholder(canvas); return
        }

        // Paint sizes
        linePaint.strokeWidth      = dp(2.5f)
        zeroLinePaint.strokeWidth  = dp(1f)
        gridPaint.strokeWidth      = dp(1f)
        depletPaint.strokeWidth    = dp(2f)
        depletPaint.pathEffect     = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        yLabelPaint.textSize       = dp(9f)
        xLabelPaint.textSize       = dp(9f)
        survivalPaint.textSize     = dp(10f)

        val pL = dp(54f); val pR = dp(10f); val pT = dp(14f); val pB = dp(28f)
        val cW = (width - pL - pR).coerceAtLeast(1f)
        val cH = (height - pT - pB).coerceAtLeast(1f)

        val n       = points.size
        val maxBal  = points.maxOf { it.balance }.coerceAtLeast(1.0).toFloat()
        val minBal  = points.minOf { it.balance }.coerceAtMost(0.0).toFloat()
        val range   = (maxBal - minBal).coerceAtLeast(1f)
        val zeroY   = pT + (1f - (-minBal / range)) * cH

        fun xOf(i: Int)   = pL + (i.toFloat() / (n - 1).coerceAtLeast(1)) * cW
        fun yOf(v: Float) = pT + (1f - ((v - minBal) / range)) * cH

        // ── Grid lines + Y labels ─────────────────────────────────────────────
        val step   = niceStep(range / 4f)
        val yStart = (Math.ceil((minBal / step).toDouble()) * step).toFloat()
        var y      = yStart
        while (y <= maxBal + step * 0.5f) {
            val py = yOf(y)
            canvas.drawLine(pL, py, pL + cW, py, gridPaint)
            canvas.drawText(fmtBalance(y), pL - dp(4f), py + yLabelPaint.textSize / 3f, yLabelPaint)
            y += step
        }

        // ── Zero reference line ───────────────────────────────────────────────
        if (minBal < 0f && maxBal > 0f) {
            canvas.drawLine(pL, zeroY, pL + cW, zeroY, zeroLinePaint)
        }

        // ── Build the bezier path ─────────────────────────────────────────────
        val xs = (0 until n).map { xOf(it) }
        val ys = points.map { yOf(it.balance.toFloat()) }
        val path = bezier(xs, ys)

        // ── Positive fill (green) ─────────────────────────────────────────────
        fillPosPaint.shader = LinearGradient(
            0f, pT, 0f, zeroY.coerceAtMost(pT + cH),
            intArrayOf(0x4422C55E.toInt(), 0x0A22C55E.toInt()), null, Shader.TileMode.CLAMP
        )
        val posClip = RectF(pL, pT, pL + cW, zeroY.coerceAtMost(pT + cH))
        canvas.save(); canvas.clipRect(posClip)
        val fillPos = Path(path)
        fillPos.lineTo(xs.last(), pT + cH); fillPos.lineTo(xs.first(), pT + cH); fillPos.close()
        canvas.drawPath(fillPos, fillPosPaint); canvas.restore()

        // ── Negative fill (red) if balance goes below 0 ───────────────────────
        if (minBal < 0f) {
            fillNegPaint.shader = LinearGradient(
                0f, zeroY, 0f, pT + cH,
                intArrayOf(0x22EF4444.toInt(), 0x44EF4444.toInt()), null, Shader.TileMode.CLAMP
            )
            val negClip = RectF(pL, zeroY.coerceAtLeast(pT), pL + cW, pT + cH)
            canvas.save(); canvas.clipRect(negClip)
            val fillNeg = Path(path)
            fillNeg.lineTo(xs.last(), pT + cH); fillNeg.lineTo(xs.first(), pT + cH); fillNeg.close()
            canvas.drawPath(fillNeg, fillNegPaint); canvas.restore()
        }

        // ── Main line ─────────────────────────────────────────────────────────
        // Green above zero, red below — split the line at zero crossing
        if (minBal < 0f && maxBal > 0f) {
            // Draw green portion
            linePaint.color = 0xFF22C55E.toInt()
            canvas.save(); canvas.clipRect(RectF(pL, pT, pL + cW, zeroY))
            canvas.drawPath(path, linePaint); canvas.restore()
            // Draw red portion
            linePaint.color = 0xFFEF4444.toInt()
            canvas.save(); canvas.clipRect(RectF(pL, zeroY, pL + cW, pT + cH))
            canvas.drawPath(path, linePaint); canvas.restore()
        } else {
            linePaint.color = if (minBal >= 0f) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            canvas.drawPath(path, linePaint)
        }

        // ── Depletion marker ──────────────────────────────────────────────────
        if (depletionMonth in 1 until n) {
            val dx = xOf(depletionMonth)
            canvas.drawLine(dx, pT, dx, pT + cH, depletPaint)
            canvas.drawCircle(dx, yOf(0f), dp(5f), depletDotPaint)
            // Label above the line
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color     = 0xFFEF4444.toInt()
                textSize  = dp(9f)
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawText("Depleted", dx, pT - dp(2f), labelPaint)
        }

        // ── Survival badge (no depletion) ─────────────────────────────────────
        if (depletionMonth < 0 && points.isNotEmpty()) {
            val lastX = xs.last(); val lastY = ys.last()
            survivalPaint.textSize = dp(9f)
            val badge = "✅ Survives"
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF16A34A.toInt(); style = Paint.Style.FILL
                textSize = dp(9f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            canvas.drawText(badge, (lastX).coerceAtMost(pL + cW - dp(30f)), lastY - dp(8f), badgePaint)
        }

        // ── X-axis month labels ───────────────────────────────────────────────
        val totalMonths = n - 1
        val everyN = when {
            totalMonths <= 12 -> 3
            totalMonths <= 36 -> 6
            else              -> 12
        }
        for (i in 0..totalMonths step everyN) {
            canvas.drawText("M$i", xOf(i), height.toFloat() - dp(5f), xLabelPaint)
        }
        // Always label last point
        canvas.drawText("M$totalMonths", xOf(totalMonths), height.toFloat() - dp(5f), xLabelPaint)
    }

    private fun drawPlaceholder(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10000000; style = Paint.Style.STROKE; strokeWidth = dp(1f) }
        for (i in 1..4) { val y = height * i / 5f; canvas.drawLine(dp(54f), y, width.toFloat() - dp(10f), y, p) }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CA3AF.toInt(); textSize = dp(12f); textAlign = Paint.Align.CENTER }
        canvas.drawText("Run a simulation to see the chart", width / 2f, height / 2f, tp)
    }

    private fun bezier(xs: List<Float>, ys: List<Float>): Path {
        val p = Path(); p.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) {
            val cx = (xs[i - 1] + xs[i]) / 2f
            p.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i])
        }
        return p
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val mag  = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
        val norm = raw / mag
        return when { norm <= 1f -> 1f; norm <= 2f -> 2f; norm <= 2.5f -> 2.5f; norm <= 5f -> 5f; else -> 10f } * mag
    }

    private fun fmtBalance(v: Float): String = when {
        v >= 1_00_00_000f  -> "₹${String.format("%.0f", v / 1_00_00_000f)}C"
        v <= -1_00_00_000f -> "-₹${String.format("%.0f", -v / 1_00_00_000f)}C"
        v >= 1_00_000f     -> "₹${String.format("%.0f", v / 1_00_000f)}L"
        v <= -1_00_000f    -> "-₹${String.format("%.0f", -v / 1_00_000f)}L"
        v >= 1_000f        -> "₹${(v / 1_000f).toInt()}K"
        v <= -1_000f       -> "-₹${(-v / 1_000f).toInt()}K"
        else               -> "₹${v.toInt()}"
    }
}
