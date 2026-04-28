package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.AiDashboardMetrics
import com.example.team_arthsetu.viewmodel.DashboardViewModel
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.example.team_arthsetu.viewmodel.WealthSummary
import com.example.team_arthsetu.viewmodel.WealthViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    // Shared with ExpenseFragment → no duplicate network hit when both are visited
    private val expenseVm: ExpenseViewModel by activityViewModels()
    private val dashboardVm: DashboardViewModel by viewModels()
    private val wealthVm: WealthViewModel by activityViewModels()

    // ── View refs ─────────────────────────────────────────────────────────────
    private lateinit var tvSavingsBadge    : TextView
    private lateinit var tvSavingsRate     : TextView
    private lateinit var tvRiskBadge       : TextView
    private lateinit var tvRiskScore       : TextView
    private lateinit var pbAiRisk          : ProgressBar
    private lateinit var tvAiInsights    : TextView
    private lateinit var tvFlowNetWorth    : TextView
    private lateinit var tvFlowEquities    : TextView
    private lateinit var tvFlowLiabilities : TextView
    private lateinit var tvFlowLiquid      : TextView
    private lateinit var tvFlowSuggestion  : TextView
    private lateinit var tvNetWorthAmount  : TextView
    private lateinit var tvNetWorthChange  : TextView
    private lateinit var lineChart         : LineChartView
    private lateinit var tvCashflowPeriod  : TextView
    private lateinit var tvCashflowSub     : TextView
    private lateinit var tvTotalIncome     : TextView
    private lateinit var tvTotalExpense    : TextView
    private lateinit var tvNetSavings      : TextView
    private lateinit var tvSavingsBadgeCash: TextView
    private lateinit var barChart          : BarChartView

    /** Current cashflow period selection */
    private var cashflowPeriod = Period.MONTHLY

    enum class Period(val label: String, val subtitle: String) {
        DAILY    ("Daily ▾",     "Income vs. Expenses · Last 7 Days"),
        WEEKLY   ("Weekly ▾",    "Income vs. Expenses · Last 6 Weeks"),
        MONTHLY  ("Monthly ▾",   "Income vs. Expenses · Monthly View (This Year)"),
        QUARTERLY("Quarterly ▾", "Income vs. Expenses · Last 4 Quarters")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        // Wire hamburger to open navigation drawer
        view.findViewById<TextView>(R.id.btnDashHamburger)?.setOnClickListener {
            (requireActivity() as? com.example.team_arthsetu.MainActivity)?.openDrawer()
        }
        setupPeriodDropdown()
        observeData()
        kickLoad()
    }

    override fun onResume() {
        super.onResume()
        wealthVm.loadAll()
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        tvSavingsBadge     = v.findViewById(R.id.tvSavingsBadge)
        tvSavingsRate      = v.findViewById(R.id.tvSavingsRate)
        tvRiskBadge        = v.findViewById(R.id.tvRiskBadge)
        tvRiskScore        = v.findViewById(R.id.tvRiskScore)
        pbAiRisk           = v.findViewById(R.id.pbAiRisk)
        tvAiInsights       = v.findViewById(R.id.tvAiInsights)
        tvFlowNetWorth     = v.findViewById(R.id.tvFlowNetWorth)
        tvFlowEquities     = v.findViewById(R.id.tvFlowEquities)
        tvFlowLiabilities  = v.findViewById(R.id.tvFlowLiabilities)
        tvFlowLiquid       = v.findViewById(R.id.tvFlowLiquid)
        tvFlowSuggestion   = v.findViewById(R.id.tvFlowSuggestion)
        tvNetWorthAmount   = v.findViewById(R.id.tvNetWorthAmount)
        tvNetWorthChange   = v.findViewById(R.id.tvNetWorthChange)
        lineChart          = v.findViewById(R.id.lineChart)
        tvCashflowPeriod   = v.findViewById(R.id.tvCashflowPeriod)
        tvCashflowSub      = v.findViewById(R.id.tvCashflowSubtitle)
        tvTotalIncome      = v.findViewById(R.id.tvTotalIncome)
        tvTotalExpense     = v.findViewById(R.id.tvTotalExpense)
        tvNetSavings       = v.findViewById(R.id.tvNetSavings)
        tvSavingsBadgeCash = v.findViewById(R.id.tvSavingsBadgeCashflow)
        barChart           = v.findViewById(R.id.barChart)
    }

    // ── Period dropdown ───────────────────────────────────────────────────────

    private fun setupPeriodDropdown() {
        tvCashflowPeriod.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, 0, 0, "Daily")
            popup.menu.add(0, 1, 1, "Weekly")
            popup.menu.add(0, 2, 2, "Monthly")
            popup.menu.add(0, 3, 3, "Quarterly")
            popup.setOnMenuItemClickListener { item ->
                cashflowPeriod = when (item.itemId) {
                    0    -> Period.DAILY
                    1    -> Period.WEEKLY
                    3    -> Period.QUARTERLY
                    else -> Period.MONTHLY
                }
                tvCashflowPeriod.text = cashflowPeriod.label
                tvCashflowSub.text    = cashflowPeriod.subtitle
                expenseVm.allTransactions.value?.let { renderCashflow(it) }
                true
            }
            popup.show()
        }
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeData() {
        expenseVm.allTransactions.observe(viewLifecycleOwner) { all ->
            renderCashflow(all)
            dashboardVm.publishFrom(all, wealthVm.summary.value)
        }
        dashboardVm.aiMetrics.observe(viewLifecycleOwner) { m ->
            renderAiMetrics(m)
        }
        wealthVm.summary.observe(viewLifecycleOwner) { s ->
            renderWealth(s)
            expenseVm.allTransactions.value?.let { dashboardVm.publishFrom(it, s) }
        }
    }

    // ── Kick load ─────────────────────────────────────────────────────────────

    private fun kickLoad() {
        dashboardVm.loadSummary()
        if (expenseVm.allTransactions.value.isNullOrEmpty()) {
            expenseVm.loadAll(requireContext())
        } else {
            expenseVm.allTransactions.value?.let { renderCashflow(it) }
        }
        wealthVm.loadAll()
    }

    // ── Cashflow rendering ────────────────────────────────────────────────────

    private fun renderCashflow(all: List<Transaction>) {
        val bars = when (cashflowPeriod) {
            Period.DAILY     -> buildDailyBars(all)
            Period.WEEKLY    -> buildWeeklyBars(all)
            Period.MONTHLY   -> buildMonthlyBars(all)
            Period.QUARTERLY -> buildQuarterlyBars(all)
        }
        barChart.setData(bars)

        // Stats box: sum the displayed bars' data range
        val periodInc  = bars.sumOf { it.income.toDouble() }
        val periodExp  = bars.sumOf { it.expense.toDouble() }
        val periodSav  = periodInc - periodExp
        val savPct     = if (periodInc > 0) ((periodSav / periodInc) * 100).toInt() else 0

        tvTotalIncome.text      = periodInc.toRupees()
        tvTotalExpense.text     = periodExp.toRupees()
        tvNetSavings.text       = periodSav.toRupees()
        tvSavingsBadgeCash.text = "✅  ${savPct}% Saved"

        // Current-month quick-stat card (always monthly)
        val cal   = Calendar.getInstance()
        val m     = cal.get(Calendar.MONTH)
        val y     = cal.get(Calendar.YEAR)
        val mTxns = all.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == m && c.get(Calendar.YEAR) == y
        }
        val mInc    = mTxns.filter { it.isCredit() }.sumOf { it.amount }
        val mExp    = mTxns.filter { it.isDebit() }.sumOf { it.amount }
        val mSavPct = if (mInc > 0) ((mInc - mExp) / mInc * 100).toInt() else 0
        tvSavingsRate.text  = "${mSavPct}%"
        tvSavingsBadge.text = "${if (mSavPct >= 0) "+" else ""}$mSavPct%"
        tvSavingsBadge.setTextColor(if (mSavPct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
    }

    private fun renderAiMetrics(m: AiDashboardMetrics?) {
        if (m == null) {
            tvRiskScore.text = "—"
            tvRiskBadge.text = "…"
            pbAiRisk.progress = 0
            tvAiInsights.text = getString(R.string.ai_insights_loading)
            return
        }
        val score = m.riskScore
        if (score != null) {
            val s = score.coerceIn(0.0, 100.0)
            tvRiskScore.text = String.format(Locale.getDefault(), "%.1f/100", s)
            pbAiRisk.progress = kotlin.math.round(s).toInt().coerceIn(0, 100)
        } else {
            tvRiskScore.text = "—"
            pbAiRisk.progress = 0
        }
        val level = m.riskLevel?.trim().orEmpty().ifEmpty { "—" }
        tvRiskBadge.text = level
        val levelLower = level.lowercase(Locale.getDefault())
        tvRiskBadge.setTextColor(
            when {
                levelLower.contains("low") -> 0xFF22C55E.toInt()
                levelLower.contains("high") -> 0xFFEF4444.toInt()
                else -> 0xFFFF9F0A.toInt()
            }
        )
        tvAiInsights.text = if (m.insights.isEmpty()) {
            getString(R.string.ai_insights_empty)
        } else {
            m.insights.joinToString("\n") { "• $it" }
        }
    }

    // ── Bar builders ──────────────────────────────────────────────────────────

    /** Last 7 individual days */
    private fun buildDailyBars(all: List<Transaction>): List<BarChartView.MonthBar> {
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        return (6 downTo 0).map { offset ->
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -offset)
            }
            val next = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val txns = all.filter { it.timestamp in c.timeInMillis until next.timeInMillis }
            BarChartView.MonthBar(
                label   = dayFmt.format(c.time).take(3),
                income  = txns.filter { it.isCredit() }.sumOf { it.amount }.toFloat(),
                expense = txns.filter { it.isDebit() }.sumOf { it.amount }.toFloat()
            )
        }
    }

    /** Last 6 ISO weeks */
    private fun buildWeeklyBars(all: List<Transaction>): List<BarChartView.MonthBar> {
        return (5 downTo 0).mapIndexed { i, offset ->
            val weekStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                add(Calendar.WEEK_OF_YEAR, -offset)
            }
            val weekEnd = (weekStart.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, 1) }
            val txns = all.filter { it.timestamp in weekStart.timeInMillis until weekEnd.timeInMillis }
            BarChartView.MonthBar(
                label   = "W${i + 1}",
                income  = txns.filter { it.isCredit() }.sumOf { it.amount }.toFloat(),
                expense = txns.filter { it.isDebit() }.sumOf { it.amount }.toFloat()
            )
        }
    }

    /** Last 6 calendar months */
    private fun buildMonthlyBars(all: List<Transaction>): List<BarChartView.MonthBar> {
        val fmt = SimpleDateFormat("MMM", Locale.getDefault())
        return (5 downTo 0).map { offset ->
            val c = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -offset)
            }
            val mo   = c.get(Calendar.MONTH)
            val yr   = c.get(Calendar.YEAR)
            val txns = all.filter {
                val tc = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                tc.get(Calendar.MONTH) == mo && tc.get(Calendar.YEAR) == yr
            }
            BarChartView.MonthBar(
                label   = fmt.format(c.time),
                income  = txns.filter { it.isCredit() }.sumOf { it.amount }.toFloat(),
                expense = txns.filter { it.isDebit() }.sumOf { it.amount }.toFloat()
            )
        }
    }

    /** Last 4 quarters */
    private fun buildQuarterlyBars(all: List<Transaction>): List<BarChartView.MonthBar> {
        val cal      = Calendar.getInstance()
        val curMonth = cal.get(Calendar.MONTH)
        val curYear  = cal.get(Calendar.YEAR)
        val curQ     = curMonth / 3  // 0-indexed quarter

        return (3 downTo 0).map { offset ->
            var q = curQ - offset
            var y = curYear
            while (q < 0) { q += 4; y-- }

            val startMonth = q * 3
            val qStart = Calendar.getInstance().apply {
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, startMonth)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val qEnd = (qStart.clone() as Calendar).apply { add(Calendar.MONTH, 3) }
            val txns = all.filter { it.timestamp in qStart.timeInMillis until qEnd.timeInMillis }
            BarChartView.MonthBar(
                label   = "Q${q + 1}'${y % 100}",
                income  = txns.filter { it.isCredit() }.sumOf { it.amount }.toFloat(),
                expense = txns.filter { it.isDebit() }.sumOf { it.amount }.toFloat()
            )
        }
    }

    // ── Wealth rendering ──────────────────────────────────────────────────────

    private fun renderWealth(s: WealthSummary) {
        tvFlowNetWorth.text    = fmtL(s.netWorth)
        tvFlowEquities.text    = fmtL(s.invested)
        tvFlowLiabilities.text = fmtL(s.totalLiabilities)
        tvFlowLiquid.text      = fmtL(s.bankBalance)

        tvFlowSuggestion.text = when {
            s.totalAssets == 0.0 ->
                "Add your first asset to see capital flow analytics"
            s.totalLiabilities > s.totalAssets * 0.5 ->
                "Liabilities exceed 50% of assets — consider debt reduction"
            s.bankBalance < s.totalAssets * 0.05 ->
                "System suggesting 12% rebalance to Liquid Cash for liquidity"
            s.avgChangePercent > 10 ->
                "Great performance! Assets growing at ${fmt1(s.avgChangePercent)}% avg."
            else ->
                "Consistent investments will compound your net worth over time"
        }

        tvNetWorthAmount.text = fmtL(s.netWorth)
        val pct    = s.avgChangePercent
        val pctStr = if (pct >= 0) "↑${fmt1(pct)}%" else "↓${fmt1(-pct)}%"
        tvNetWorthChange.text = pctStr
        tvNetWorthChange.setTextColor(if (pct >= 0) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())

        buildLineChart(s.netWorth, s.avgChangePercent)
    }

    private fun buildLineChart(currentNW: Double, avgChangePct: Double) {
        val fmt     = SimpleDateFormat("MMM", Locale.getDefault())
        val mGrowth = (avgChangePct / 100.0) / 12.0
        val pts = (5 downTo 0).mapIndexed { i, offset ->
            val c = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -offset)
            }
            val power = (5 - i).toDouble()
            val hist  = if (1 + mGrowth > 0) currentNW / Math.pow(1 + mGrowth, power)
                        else currentNW
            LineChartView.Point(fmt.format(c.time), hist.toFloat().coerceAtLeast(0f))
        }
        lineChart.setData(pts)
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private fun fmtL(v: Double) = when {
        v >= 1_00_00_000 -> "₹${fmt1(v / 1_00_00_000)}Cr"
        v >= 1_00_000    -> "₹${fmt1(v / 1_00_000)}L"
        v >= 1_000       -> "₹${fmt1(v / 1_000)}K"
        else             -> "₹${v.toInt()}"
    }

    private fun fmt1(d: Double) = String.format("%.1f", d)
}
