package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Goal
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.AppSnackbar
import com.example.team_arthsetu.utils.ProfileStore
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.AllocationSlice
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.example.team_arthsetu.viewmodel.WealthSummary
import com.example.team_arthsetu.viewmodel.WealthViewModel
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import kotlin.math.pow
import kotlinx.coroutines.launch

class GoalsFragment : Fragment(R.layout.fragment_goals) {

    private val expenseVm: ExpenseViewModel by activityViewModels()
    private val wealthVm : WealthViewModel  by viewModels()
    private val firestoreRepo = FirestoreRepository()

    // ── View refs ─────────────────────────────────────────────────────────────
    private lateinit var etGoalName       : EditText
    private lateinit var etGoalDescription  : EditText
    private lateinit var etTarget         : EditText
    private lateinit var etTimeframe      : EditText
    private lateinit var seekRisk         : SeekBar
    private lateinit var tvRiskLabel      : TextView
    private lateinit var btnCalculate     : MaterialButton
    private lateinit var btnSaveGoal      : MaterialButton
    private lateinit var dualChart        : DualLineChartView
    private lateinit var cardFeasibility  : CardView
    private lateinit var tvFeasIcon       : TextView
    private lateinit var tvFeasTitle      : TextView
    private lateinit var tvFeasMsg        : TextView
    private lateinit var tvFeasTip        : TextView
    private lateinit var tvSipTarget      : TextView
    private lateinit var tvSipDesc        : TextView
    private lateinit var tvAllocationShift: TextView
    private lateinit var tvAllocationDesc : TextView
    private lateinit var tvExpenseShift   : TextView
    private lateinit var tvExpenseDesc    : TextView

    // ── Cached financial state ────────────────────────────────────────────────
    private var latestWealth    : WealthSummary?        = null
    private var monthlyIncome   : Double                = 0.0
    private var monthlyExpense  : Double                = 0.0
    // top-category map: category → total spend this month (debit only)
    private var categoryTotals  : Map<String, Double>   = emptyMap()
    // Only show feasibility card AFTER user explicitly clicks Calculate
    private var userHasCalculated: Boolean              = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        // Wire hamburger to open navigation drawer
        view.findViewById<android.widget.TextView>(R.id.btnGoalsHamburger)?.setOnClickListener {
            (requireActivity() as? com.example.team_arthsetu.MainActivity)?.openDrawer()
        }
        setupRiskSlider()
        setupCalculateButton()
        setupSaveGoal()
        observeData()
        kickLoad()
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        etGoalName        = v.findViewById(R.id.etGoalName)
        etGoalDescription = v.findViewById(R.id.etGoalDescription)
        etTarget          = v.findViewById(R.id.etTarget)
        etTimeframe       = v.findViewById(R.id.etTimeframe)
        seekRisk          = v.findViewById(R.id.seekRisk)
        tvRiskLabel       = v.findViewById(R.id.tvRiskLabel)
        btnCalculate      = v.findViewById(R.id.btnCalculate)
        btnSaveGoal       = v.findViewById(R.id.btnSaveGoal)
        dualChart         = v.findViewById(R.id.dualChart)
        cardFeasibility   = v.findViewById(R.id.cardFeasibility)
        tvFeasIcon        = v.findViewById(R.id.tvFeasIcon)
        tvFeasTitle       = v.findViewById(R.id.tvFeasTitle)
        tvFeasMsg         = v.findViewById(R.id.tvFeasMsg)
        tvFeasTip         = v.findViewById(R.id.tvFeasTip)
        tvSipTarget       = v.findViewById(R.id.tvSipTarget)
        tvSipDesc         = v.findViewById(R.id.tvSipDesc)
        tvAllocationShift = v.findViewById(R.id.tvAllocationShift)
        tvAllocationDesc  = v.findViewById(R.id.tvAllocationDesc)
        tvExpenseShift    = v.findViewById(R.id.tvExpenseShift)
        tvExpenseDesc     = v.findViewById(R.id.tvExpenseDesc)
    }

    // ── Risk slider ───────────────────────────────────────────────────────────

    private fun setupRiskSlider() {
        updateRiskLabel(seekRisk.progress)
        seekRisk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateRiskLabel(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateRiskLabel(progress: Int) {
        val (label, ret, color) = when {
            progress < 34 -> Triple("Aggressive",   "~15% p.a.", 0xFF4C35DC.toInt())
            progress < 67 -> Triple("Moderate",     "~12% p.a.", 0xFFFF9F0A.toInt())
            else          -> Triple("Conservative", "~8% p.a.",  0xFF22C55E.toInt())
        }
        tvRiskLabel.text = "$label — Est. return $ret"
        tvRiskLabel.setTextColor(color)
    }

    // ── Calculate button ──────────────────────────────────────────────────────

    private fun setupCalculateButton() {
        btnCalculate.setOnClickListener {
            userHasCalculated = true          // unlock feasibility card from now on
            btnCalculate.isEnabled = false
            btnCalculate.text = "⏳  Calculating…"
            btnCalculate.postDelayed({
                runCalculation()
                dualChart.alpha = 0.3f
                dualChart.animate().alpha(1f).setDuration(400).start()
                btnCalculate.isEnabled = true
                btnCalculate.text = "⚡  RE-CALCULATE AI PATH"
            }, 300)
        }
    }

    // ── Save goal to Firestore ────────────────────────────────────────────────

    private fun setupSaveGoal() {
        btnSaveGoal.setOnClickListener {
            val name = etGoalName.text.toString().trim()
            if (name.isEmpty()) {
                AppSnackbar.show(btnSaveGoal, "Enter a goal name")
                return@setOnClickListener
            }
            val targetLakhs = etTarget.text.toString().toDoubleOrNull()?.coerceAtLeast(0.1)
            if (targetLakhs == null) {
                AppSnackbar.show(btnSaveGoal, "Enter target amount (₹ Lakhs)")
                return@setOnClickListener
            }
            val years = etTimeframe.text.toString().toIntOrNull()?.coerceIn(1, 40)
            if (years == null) {
                AppSnackbar.show(btnSaveGoal, "Enter timeframe (years)")
                return@setOnClickListener
            }
            if (!userHasCalculated) {
                AppSnackbar.show(btnSaveGoal, "Tap Calculate first to generate your AI plan")
                return@setOnClickListener
            }

            val targetAmt = targetLakhs * 1_00_000.0
            val cal = Calendar.getInstance()
            cal.add(Calendar.YEAR, years)
            val targetDateMs = cal.timeInMillis
            val currentAmt = latestWealth?.netWorth ?: 0.0
            val desc = etGoalDescription.text.toString().trim()
            val insight = buildAiInsightString()

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    firestoreRepo.saveGoal(
                        Goal(
                            goalName = name,
                            description = desc,
                            targetAmount = targetAmt,
                            currentAmount = currentAmt,
                            targetDate = targetDateMs,
                            aiInsight = insight
                        )
                    )
                    AppSnackbar.show(btnSaveGoal, "Goal saved to cloud")
                } catch (e: Exception) {
                    AppSnackbar.showLong(btnSaveGoal, e.message ?: "Save failed")
                }
            }
        }
    }

    private fun buildAiInsightString(): String = buildString {
        val nw = latestWealth?.netWorth ?: 0.0
        val liab = latestWealth?.totalLiabilities ?: 0.0
        val mSave = (monthlyIncome - monthlyExpense).coerceAtLeast(0.0)
        append("— Snapshot (used for this plan) —\n")
        append("Net worth: ").append(fmt(nw)).append("\n")
        append("Liabilities: ").append(fmt(liab)).append("\n")
        append("Monthly income: ").append(fmt(monthlyIncome)).append("\n")
        append("Monthly expenses: ").append(fmt(monthlyExpense)).append("\n")
        append("Monthly savings: ").append(fmt(mSave)).append("\n\n")
        if (userHasCalculated) {
            append(tvFeasTitle.text).append("\n\n")
            append(tvFeasMsg.text).append("\n\n")
            append(tvFeasTip.text).append("\n\n---\n\n")
        }
        append("Monthly SIP target: ").append(tvSipTarget.text).append("\n")
        append(tvSipDesc.text).append("\n\n")
        append(tvAllocationShift.text).append(": ").append(tvAllocationDesc.text).append("\n\n")
        append(tvExpenseShift.text).append(": ").append(tvExpenseDesc.text)
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun observeData() {
        wealthVm.summary.observe(viewLifecycleOwner) { s ->
            latestWealth = s
            runCalculation()
        }
        expenseVm.allTransactions.observe(viewLifecycleOwner) { all ->
            extractMonthlyData(all)
            runCalculation()
        }
    }

    private fun extractMonthlyData(all: List<com.example.team_arthsetu.model.Transaction>) {
        val cal = Calendar.getInstance()
        val m   = cal.get(Calendar.MONTH); val y = cal.get(Calendar.YEAR)
        val cur = all.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == m && c.get(Calendar.YEAR) == y
        }
        monthlyIncome  = cur.filter { it.type == "credit" }.sumOf { it.amount }
        monthlyExpense = cur.filter { it.type == "debit"  }.sumOf { it.amount }
        // Build per-category spending map (debits only)
        categoryTotals = cur.filter { it.type == "debit" }
            .groupBy { it.category }
            .mapValues { (_, txns) -> txns.sumOf { it.amount } }
        // Fall back to profile income if no credited transactions this month
        if (monthlyIncome < 1_000) {
            val profileIncome = ProfileStore.load(requireContext()).monthlyIncome
            if (profileIncome > 0) monthlyIncome = profileIncome
        }
    }

    private fun kickLoad() {
        wealthVm.loadAll()
        if (expenseVm.allTransactions.value.isNullOrEmpty()) {
            expenseVm.loadAll(requireContext())
        } else {
            extractMonthlyData(expenseVm.allTransactions.value ?: emptyList())
        }
        dualChart.post { runCalculation() }
    }

    // ── Core AI Calculation ───────────────────────────────────────────────────

    private fun runCalculation() {
        // Target entered in ₹ Lakhs (1 Lakh = ₹1,00,000). Default 1000 Lakhs ≙ former 10 Cr default.
        val targetLakhs = etTarget.text.toString().toDoubleOrNull()?.coerceAtLeast(0.1) ?: 1000.0
        val years       = etTimeframe.text.toString().toIntOrNull()?.coerceIn(1, 40) ?: 10
        val risk        = seekRisk.progress

        val targetAmt   = targetLakhs * 1_00_000.0
        val currentNW    = latestWealth?.netWorth         ?: 0.0
        val totalInv     = latestWealth?.totalAssets      ?: 0.0
        val liabilities  = latestWealth?.totalLiabilities ?: 0.0
        val allocation   = latestWealth?.allocation       ?: emptyList()
        val mSavings     = (monthlyIncome - monthlyExpense).coerceAtLeast(0.0)
        val savingsRate  = if (monthlyIncome > 0) (mSavings / monthlyIncome * 100).toInt() else 0

        // Return rates based on risk preference
        val aiAnnual   = when { risk < 34 -> 0.15; risk < 67 -> 0.12; else -> 0.08 }
        val stdAnnual  = 0.08
        val aiMonthly  = aiAnnual  / 12.0
        val stdMonthly = stdAnnual / 12.0
        val months     = years * 12

        // ── Standard path (current NW + current savings @ 8%) ─────────────────
        val stdPoints = (0..years).map { yr ->
            val m  = yr.toDouble() * 12
            val nw = currentNW * (1 + stdMonthly).pow(m)
            val sp = if (stdMonthly > 0) mSavings * ((1 + stdMonthly).pow(m) - 1) / stdMonthly
                     else mSavings * m
            (nw + sp).toFloat().coerceAtLeast(0f)
        }

        // ── AI path: calculate required extra monthly SIP ─────────────────────
        val fvNwAi  = currentNW * (1 + aiMonthly).pow(months.toDouble())
        val fvSipAi = if (aiMonthly > 0)
            mSavings * ((1 + aiMonthly).pow(months.toDouble()) - 1) / aiMonthly
        else mSavings * months.toDouble()

        val remaining = (targetAmt - fvNwAi - fvSipAi).coerceAtLeast(0.0)
        val extraSip  = if (aiMonthly > 0 && months > 0)
            remaining * aiMonthly / ((1 + aiMonthly).pow(months.toDouble()) - 1)
        else remaining / months.coerceAtLeast(1)

        val totalAiSip = mSavings + extraSip

        val aiPoints = (0..years).map { yr ->
            val m  = yr.toDouble() * 12
            val nw = currentNW * (1 + aiMonthly).pow(m)
            val sp = if (aiMonthly > 0) totalAiSip * ((1 + aiMonthly).pow(m) - 1) / aiMonthly
                     else totalAiSip * m
            (nw + sp).toFloat().coerceAtLeast(0f)
        }

        // ── Update chart ──────────────────────────────────────────────────────
        dualChart.setData(aiPoints, stdPoints, Calendar.getInstance().get(Calendar.YEAR))

        // ── Feasibility — only visible after user explicitly taps Calculate ───
        if (userHasCalculated) {
            val ratio = if (monthlyIncome > 0) totalAiSip / monthlyIncome else -1.0
            showFeasibility(
                ratio,
                totalAiSip,
                years,
                targetAmt,
                targetLakhs,
                stdPoints.last().toDouble()
            )
        }

        // ── AI Action plan ────────────────────────────────────────────────────
        updateActionPlan(
            totalAiSip    = totalAiSip,
            mSavings      = mSavings,
            extraSip      = extraSip,
            risk          = risk,
            years         = years,
            savingsRate   = savingsRate,
            monthlyIncome = monthlyIncome,
            allocation    = allocation,
            liabilities   = liabilities,
            currentNW     = currentNW,
            aiMonthly     = aiMonthly,
            months        = months
        )
    }

    // ── Feasibility card (only shown after button tap) ────────────────────────

    private fun showFeasibility(
        ratio       : Double,
        totalSip    : Double,
        years       : Int,
        targetAmt   : Double,
        targetLakhs : Double,
        stdFinal    : Double
    ) {
        val goalLabel = formatLakhsGoal(targetLakhs)
        cardFeasibility.visibility = View.VISIBLE   // user just tapped, so always show

        when {
            // Standard 8% path already hits the goal
            stdFinal >= targetAmt -> {
                cardFeasibility.setCardBackgroundColor(0xFFE8FAF0.toInt())
                tvFeasIcon.text  = "🎯"
                tvFeasTitle.text = "Easily Achievable!"
                tvFeasTitle.setTextColor(0xFF16A34A.toInt())
                tvFeasMsg.text   = "Your current wealth + savings will already reach $goalLabel in $years " +
                    "years at a standard 8% rate — without any extra investment."
                tvFeasTip.text   = "💡 Tip: A small top-up SIP of ${fmt(totalSip * 0.2)}/month will hit this goal ${maxOf(1,(years*0.1).toInt())} year(s) sooner."
            }
            // No income data — can't judge yet
            ratio < 0 -> {
                cardFeasibility.setCardBackgroundColor(0xFFF0F4FF.toInt())
                tvFeasIcon.text  = "ℹ️"
                tvFeasTitle.text = "Set Up Income Data"
                tvFeasTitle.setTextColor(0xFF4C35DC.toInt())
                tvFeasMsg.text   = "We need your monthly income to judge feasibility. Log credit transactions or " +
                    "set income in your Profile so the AI can give you an honest assessment."
                tvFeasTip.text   = "💡 Go to ☰ → Edit Profile → Monthly Income."
            }
            ratio <= 0.25 -> {
                cardFeasibility.setCardBackgroundColor(0xFFEEF0FF.toInt())
                tvFeasIcon.text  = "✅"
                tvFeasTitle.text = "Achievable Goal"
                tvFeasTitle.setTextColor(0xFF4C35DC.toInt())
                tvFeasMsg.text   = "Required monthly investment of ${totalSip.toRupees()} is only ${(ratio * 100).toInt()}% " +
                    "of your income — very manageable. Set up an auto-debit SIP and you'll hit $goalLabel in $years years."
                tvFeasTip.text   = "💡 Use index funds (Nifty 50 ETF) for low-cost, consistent compounding."
            }
            ratio <= 0.50 -> {
                cardFeasibility.setCardBackgroundColor(0xFFFFF9E6.toInt())
                tvFeasIcon.text  = "⚡"
                tvFeasTitle.text = "Ambitious — But Doable"
                tvFeasTitle.setTextColor(0xFFB45309.toInt())
                tvFeasMsg.text   = "$goalLabel in $years years needs ${totalSip.toRupees()}/month " +
                    "(${(ratio * 100).toInt()}% of income). Tight but achievable with consistent budgeting."
                tvFeasTip.text   = "💡 Try extending to ${years + 2} years — it drops the required SIP by ~15%."
            }
            ratio <= 1.0 -> {
                cardFeasibility.setCardBackgroundColor(0xFFFFF2F0.toInt())
                tvFeasIcon.text  = "⚠️"
                tvFeasTitle.text = "Very Challenging"
                tvFeasTitle.setTextColor(0xFFDC2626.toInt())
                tvFeasMsg.text   = "This goal needs ${totalSip.toRupees()}/month — ${(ratio * 100).toInt()}% of income. " +
                    "Almost all disposable income goes toward one goal, leaving no safety net."
                tvFeasTip.text   = "💡 Plan B: Target ${formatLakhsGoal(targetLakhs * 0.65)} first (more manageable), then scale."
            }
            else -> {
                // ratio > 1.0 — mathematically very hard
                cardFeasibility.setCardBackgroundColor(0xFFFFF0F0.toInt())
                tvFeasIcon.text  = "🚫"
                tvFeasTitle.text = "Not Feasible in $years Years"
                tvFeasTitle.setTextColor(0xFFDC2626.toInt())
                tvFeasMsg.text   = "Achieving $goalLabel in $years years requires ${totalSip.toRupees()}/month, " +
                    "which is ${(ratio * 100).toInt()}% of your current income. This exceeds what's mathematically possible."
                tvFeasTip.text   = "💡 Realistic Plan: Set ${formatLakhsGoal(targetLakhs * 0.5)} as a ${years}-year milestone, " +
                    "or extend the timeframe to ${(years * 1.6).toInt()} years for the full $goalLabel target."
            }
        }
    }

    /** Display target as ₹ Lakhs (matches input). */
    private fun formatLakhsGoal(lakhs: Double): String = "₹${f1(lakhs)} Lakhs"

    // ── AI Action Plan ────────────────────────────────────────────────────────

    private fun updateActionPlan(
        totalAiSip   : Double,
        mSavings     : Double,
        extraSip     : Double,
        risk         : Int,
        years        : Int,
        savingsRate  : Int,
        monthlyIncome: Double,
        allocation   : List<AllocationSlice>,
        liabilities  : Double,
        currentNW    : Double,
        aiMonthly    : Double,
        months       : Int
    ) {
        // ── 1. Monthly SIP Target ──────────────────────────────────────────────
        tvSipTarget.text = totalAiSip.toRupees()

        tvSipDesc.text = when {
            monthlyIncome < 1_000 && currentNW == 0.0 ->
                "Add your income in Profile and log transactions — the AI will personalise this target for you."
            mSavings < 500 ->
                "Start with ${fmt(totalAiSip)}/month in a SIP. Consistency over years beats lump-sum timing."
            extraSip < 500 ->
                "You're already on track! Your current ${fmt(mSavings)}/month savings will reach this goal."
            else -> {
                val pct = ((extraSip / mSavings) * 100).toInt().coerceAtMost(999)
                "Top up by ${fmt(extraSip)}/month ($pct% extra over your current ${fmt(mSavings)}). " +
                "That unlocks the ${if (risk < 34) "15%" else if (risk < 67) "12%" else "8%"} AI-optimised return path."
            }
        }

        // ── 2. Asset Allocation Shift (based on REAL allocation data) ─────────
        allocationAdvice(allocation, risk, years, liabilities, currentNW)

        // ── 3. Expense Optimisation (based on REAL category spending) ─────────
        expenseAdvice(savingsRate, monthlyIncome, mSavings, extraSip, aiMonthly, months)
    }

    // ── Allocation advice using actual wealth slices ──────────────────────────

    private fun allocationAdvice(
        allocation : List<AllocationSlice>,
        risk       : Int,
        years      : Int,
        liabilities: Double,
        currentNW  : Double
    ) {
        val debtRatio = if (currentNW > 0) liabilities / currentNW else 0.0

        // Urgent: high debt overrides everything
        if (debtRatio > 0.5) {
            tvAllocationShift.text = "⚠ Pay Down High Debt First"
            tvAllocationDesc.text  =
                "Liabilities are ${(debtRatio * 100).toInt()}% of your net worth (₹${fmt(liabilities)}). " +
                "Clearing high-interest debt first gives a guaranteed return equal to its interest rate — " +
                "better than most market investments."
            return
        }

        // No wealth data yet
        if (allocation.isEmpty()) {
            val ideal = idealAllocation(risk)
            tvAllocationShift.text = "Build Your Portfolio"
            tvAllocationDesc.text  =
                "No assets found. For a ${riskLabel(risk)} $years-year goal, target: " +
                "${ideal.first}% Equity (Stocks/MF), ${ideal.second}% Debt/FD, ${ideal.third}% Gold/Other."
            return
        }

        // Real allocation calculations
        val equityPct   = allocation.filter { it.label in listOf("Stocks", "Mutual Funds") }.sumOf { it.percent.toDouble() }
        val cashPct     = allocation.filter { it.label in listOf("Cash") }.sumOf { it.percent.toDouble() }
        val goldPct     = allocation.filter { it.label in listOf("Gold") }.sumOf { it.percent.toDouble() }
        val cryptoPct   = allocation.filter { it.label in listOf("Crypto") }.sumOf { it.percent.toDouble() }
        val realEstPct  = allocation.filter { it.label in listOf("Real Estate") }.sumOf { it.percent.toDouble() }

        val (idealEquity, idealDebt, idealOther) = idealAllocation(risk)
        val totalAssets = allocation.sumOf { it.amount }

        val equityGap  = idealEquity - equityPct   // positive = need more equity
        val cashExcess = cashPct - idealDebt        // positive = too much cash/FD

        val title: String
        val desc : String

        when {
            // Too much cash/FD sitting idle
            cashExcess > 15 -> {
                val moveAmt = totalAssets * (cashExcess / 100)
                title = "Move ${cashExcess.toInt()}% Cash → ${if (risk < 67) "Equity" else "Short Bonds"}"
                desc  = "You have ${cashPct.toInt()}% in Cash/FD (₹${fmt(moveAmt)} excess vs. ${idealDebt}% target). " +
                    "Shift ₹${fmt(moveAmt)} to ${if (risk < 34) "Mid Cap funds" else if (risk < 67) "Nifty 50 index" else "short-duration debt funds"} " +
                    "to optimise returns for your $years-year goal."
            }
            // Equity underweight
            equityGap > 15 -> {
                val moveAmt = totalAssets * (equityGap / 100)
                title = "Add ${equityGap.toInt()}% More Equity"
                desc  = "Current equity: ${equityPct.toInt()}%, target for ${riskLabel(risk)}: $idealEquity%. " +
                    "Redirect ₹${fmt(moveAmt)} (via SIP or lump-sum) into " +
                    "${if (risk < 34) "Mid/Small Cap funds" else "Nifty 50 + Flexi Cap funds"} to close the gap."
            }
            // Too much crypto (volatile, risky)
            cryptoPct > 20 -> {
                title = "Trim Crypto to ≤20%"
                desc  = "Crypto is ${cryptoPct.toInt()}% of your portfolio — above safe limits for a $years-year goal. " +
                    "Book partial profits and shift excess to index funds to reduce volatility risk."
            }
            // Well balanced
            else -> {
                title = "✅ Portfolio Aligned (${equityPct.toInt()}% Equity)"
                desc  = "Your current allocation — ${equityPct.toInt()}% Equity, ${cashPct.toInt()}% Cash, " +
                    "${goldPct.toInt()}% Gold, ${realEstPct.toInt()}% Real Estate — aligns well with your " +
                    "${riskLabel(risk)} profile. Review every 6 months and rebalance if any slice drifts >5%."
            }
        }

        tvAllocationShift.text = title
        tvAllocationDesc.text  = desc
    }

    // ── Expense advice using REAL category spend data ─────────────────────────

    private fun expenseAdvice(
        savingsRate  : Int,
        monthlyIncome: Double,
        mSavings     : Double,
        extraSip     : Double,
        aiMonthly    : Double,
        months       : Int
    ) {
        if (monthlyIncome < 1_000) {
            tvExpenseShift.text = "Track Income & Expenses"
            tvExpenseDesc.text  = "Log credit transactions or set monthly income in Profile → the AI will show " +
                "exactly which categories to cut to fund your goal."
            return
        }

        // If no extra investment needed — you're on track
        if (extraSip < 500) {
            tvExpenseShift.text = "✅ No Cuts Needed"
            tvExpenseDesc.text  = "Your current savings rate ($savingsRate%) already covers this goal. " +
                "Keep expenses steady and invest windfalls (bonus/tax refund) for an early finish."
            return
        }

        // Find the biggest discretionary spending category from real data
        val discretionary = setOf("Food", "Entertainment", "Shopping", "Transport")
        val topCat = categoryTotals
            .filter { it.key in discretionary }
            .maxByOrNull { it.value }

        if (topCat != null) {
            val catName  = topCat.key
            val catSpend = topCat.value
            val cut20    = catSpend * 0.20    // 20% reduction
            val cut30    = catSpend * 0.30

            // How many months would it save if we invested the cut amount?
            val yearsShaved = if (aiMonthly > 0 && extraSip > 0)
                (cut20 / extraSip * months / 12.0).toInt().coerceAtMost(99)
            else 0

            val advice = when {
                cut20 >= extraSip -> {
                    // 20% cut alone covers the gap
                    "Your $catName bill is ₹${fmt(catSpend)}/month. " +
                    "Cutting it by just 20% (₹${fmt(cut20)}) fully funds the ₹${fmt(extraSip)} monthly gap. " +
                    "Use the 48-hour rule — wait 2 days before any non-essential $catName purchase."
                }
                cut30 >= extraSip * 0.7 -> {
                    "Trim $catName by 30% (₹${fmt(cut30)}) — covers ${((cut30/extraSip)*100).toInt()}% of the gap. " +
                    "Combine with a ₹${fmt(extraSip - cut30)} cut elsewhere to fully fund your goal."
                }
                else -> {
                    "$catName (₹${fmt(catSpend)}) is your biggest discretionary spend. " +
                    "A 20% cut saves ₹${fmt(cut20)}/month. Stack that with income growth to bridge " +
                    "the remaining ₹${fmt((extraSip - cut20).coerceAtLeast(0.0))} gap."
                }
            }

            tvExpenseShift.text = "Cut $catName by 20–30%"
            tvExpenseDesc.text  = advice +
                if (yearsShaved > 0) " This alone could shave $yearsShaved+ year(s) off your timeline." else ""

        } else {
            // No discretionary data — give a generic savings rate nudge
            val targetSavings = monthlyIncome * 0.35
            val cutNeeded     = (targetSavings - mSavings).coerceAtLeast(0.0)
            tvExpenseShift.text = "Raise Savings to 35%"
            tvExpenseDesc.text  = "Current savings rate: $savingsRate%. Cutting expenses by ₹${fmt(cutNeeded)}/month " +
                "raises it to the recommended 35% mark — adding ₹${fmt(cutNeeded * 12)} to your annual investment pot."
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns (idealEquity%, idealDebt%, idealOther%) for a given risk slider */
    private fun idealAllocation(risk: Int): Triple<Int, Int, Int> = when {
        risk < 34 -> Triple(80, 10, 10)   // Aggressive
        risk < 67 -> Triple(60, 30, 10)   // Moderate
        else      -> Triple(40, 45, 15)   // Conservative
    }

    private fun riskLabel(risk: Int) = when {
        risk < 34 -> "Aggressive"
        risk < 67 -> "Moderate"
        else      -> "Conservative"
    }

    private fun fmt(v: Double) = when {
        v >= 1_00_00_000 -> "${f1(v / 1_00_00_000)}Cr"
        v >= 1_00_000    -> "${f1(v / 1_00_000)}L"
        v >= 1_000       -> "${f1(v / 1_000)}K"
        else             -> "₹${v.toInt()}"
    }

    private fun f1(d: Double) = String.format("%.1f", d)
}
