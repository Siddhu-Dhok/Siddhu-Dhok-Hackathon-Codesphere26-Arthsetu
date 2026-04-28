package com.example.team_arthsetu.ui

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.SimInput
import com.example.team_arthsetu.model.SimResult
import com.example.team_arthsetu.repository.SimulationRepository
import com.example.team_arthsetu.utils.AppSnackbar
import com.example.team_arthsetu.utils.SimCategoryStore
import com.example.team_arthsetu.utils.UserSimCategory
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.example.team_arthsetu.viewmodel.SimulationViewModel
import com.example.team_arthsetu.viewmodel.WealthViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimulationFragment : Fragment(R.layout.fragment_simulation) {

    private val simVm    : SimulationViewModel by viewModels()
    private val expenseVm: ExpenseViewModel    by activityViewModels()
    private val wealthVm : WealthViewModel     by viewModels()

    // ── Scenario chips ───────────────────────────────────────────────────────
    private lateinit var chipJobLoss   : LinearLayout
    private lateinit var chipMedical   : LinearLayout
    private lateinit var chipIncomeDrop: LinearLayout
    private lateinit var chipInvestment: LinearLayout
    private lateinit var chipEmiHike   : LinearLayout
    private lateinit var llScenarioChips: LinearLayout
    private lateinit var fabAddCategory: FloatingActionButton

    /** When set, cloud save uses this as [SimResult.scenarioType] (user-defined label). */
    private var customSaveLabel: String? = null

    // ── Input groups ─────────────────────────────────────────────────────────
    private lateinit var tvScenarioIcon : TextView
    private lateinit var tvInputTitle   : TextView
    private lateinit var tvInputDesc    : TextView
    private lateinit var groupJobLoss   : LinearLayout
    private lateinit var groupMedical   : LinearLayout
    private lateinit var groupIncomeDrop: LinearLayout
    private lateinit var groupInvestment: LinearLayout
    private lateinit var groupEmiHike   : LinearLayout

    // Job Loss
    private lateinit var etJLSavings  : TextInputEditText
    private lateinit var etJLExpenses : TextInputEditText
    private lateinit var etJLEmi      : TextInputEditText
    private lateinit var etJLFallback : TextInputEditText

    // Medical
    private lateinit var etMedSavings   : TextInputEditText
    private lateinit var etMedInsurance : TextInputEditText
    private lateinit var etMedCost      : TextInputEditText
    private lateinit var etMedIncome    : TextInputEditText
    private lateinit var etMedExpenses  : TextInputEditText

    // Income Drop
    private lateinit var etIDIncome   : TextInputEditText
    private lateinit var etIDExpenses : TextInputEditText
    private lateinit var etIDEmi      : TextInputEditText
    private lateinit var etIDSavings  : TextInputEditText
    private lateinit var seekIDDrop   : SeekBar
    private lateinit var tvIDDropLabel: TextView

    // Investment Crash
    private lateinit var etInvPortfolio  : TextInputEditText
    private lateinit var etInvSip        : TextInputEditText
    private lateinit var etInvReturn     : TextInputEditText
    private lateinit var seekInvCrash    : SeekBar
    private lateinit var tvInvCrashLabel : TextView

    // EMI Rate Hike
    private lateinit var etEmiCurrent     : TextInputEditText
    private lateinit var etEmiOutstanding : TextInputEditText
    private lateinit var etEmiIncome      : TextInputEditText
    private lateinit var etEmiExpenses    : TextInputEditText
    private lateinit var etEmiSavings     : TextInputEditText
    private lateinit var seekEmiHike      : SeekBar
    private lateinit var tvEmiHikeLabel   : TextView

    // Result views
    private lateinit var btnRunSim     : MaterialButton
    private lateinit var btnSaveSim    : MaterialButton
    private lateinit var layoutResults : LinearLayout
    private lateinit var tvResultIcon  : TextView
    private lateinit var tvResultTitle : TextView
    private lateinit var tvResultSub   : TextView
    private lateinit var tvStatMonths  : TextView
    private lateinit var tvStatDate    : TextView
    private lateinit var tvStatBalance : TextView
    private lateinit var tvStatBalanceLabel: TextView
    private lateinit var simChart      : SimChartView
    private lateinit var llInsights    : LinearLayout

    private var currentScenario = Scenario.JOB_LOSS

    enum class Scenario { JOB_LOSS, MEDICAL, INCOME_DROP, INVESTMENT, EMI_HIKE }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupChips()
        refreshCustomChips()
        setupFabAddCategory()
        setupSliders()
        setupButtons()
        observeViewModel()
        prefill()
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { SimulationRepository().getHistory().size }.getOrDefault(0)
            }
            view?.findViewById<TextView>(R.id.tvSimCount)?.text = "$n saved"
        }
    }

    private fun setupFabAddCategory() {
        fabAddCategory.setOnClickListener { showAddCategoryDialog() }
    }

    private fun showAddCategoryDialog() {
        val dlgView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_sim_category, null, false)
        val etName = dlgView.findViewById<TextInputEditText>(R.id.etSimCatName)
        val etEmoji = dlgView.findViewById<TextInputEditText>(R.id.etSimCatEmoji)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add scenario")
            .setView(dlgView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    AppSnackbar.show(requireView(), "Enter a scenario name")
                    return@setOnClickListener
                }
                val emoji = etEmoji.text?.toString()?.trim().orEmpty().ifBlank { "✨" }
                val created = SimCategoryStore.add(requireContext(), name, emoji)
                dialog.dismiss()
                refreshCustomChips()
                selectUserCategory(created)
                AppSnackbar.show(requireView(), "Scenario added — tap chips to change type, then run")
            }
        }
        dialog.show()
    }

    private fun refreshCustomChips() {
        while (llScenarioChips.childCount > 5) {
            llScenarioChips.removeViewAt(5)
        }
        for (cat in SimCategoryStore.getAll(requireContext())) {
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_sim_custom_chip, llScenarioChips, false)
            chip.tag = "custom_${cat.id}"
            chip.findViewById<TextView>(R.id.tvCustomChipEmoji).text = cat.emoji
            chip.findViewById<TextView>(R.id.tvCustomChipTitle).text = cat.title
            chip.setOnClickListener { selectUserCategory(cat) }
            llScenarioChips.addView(chip)
        }
    }

    private fun selectUserCategory(cat: UserSimCategory) {
        val base = runCatching { Scenario.valueOf(cat.baseScenarioName) }.getOrDefault(Scenario.JOB_LOSS)
        currentScenario = base
        customSaveLabel = cat.title.trim()
        setAllChipsInactive()
        val v = llScenarioChips.findViewWithTag<View>("custom_${cat.id}")
        if (v != null) setChipActive(v)
        applyScenarioMetaAndVisibility(base)
        layoutResults.visibility = View.GONE
    }

    private fun setAllChipsInactive() {
        for (i in 0 until llScenarioChips.childCount) {
            val chip = llScenarioChips.getChildAt(i)
            chip.setBackgroundResource(R.drawable.bg_sim_chip_inactive)
            ((chip as? ViewGroup)?.getChildAt(1) as? TextView)
                ?.setTextColor(Color.parseColor("#4B5563"))
        }
    }

    private fun setChipActive(chip: View) {
        chip.setBackgroundResource(R.drawable.bg_sim_chip_active)
        ((chip as? ViewGroup)?.getChildAt(1) as? TextView)?.setTextColor(Color.WHITE)
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        chipJobLoss    = v.findViewById(R.id.chipJobLoss)
        chipMedical    = v.findViewById(R.id.chipMedical)
        chipIncomeDrop = v.findViewById(R.id.chipIncomeDrop)
        chipInvestment = v.findViewById(R.id.chipInvestment)
        chipEmiHike    = v.findViewById(R.id.chipEmiHike)
        llScenarioChips = v.findViewById(R.id.llScenarioChips)
        fabAddCategory = v.findViewById(R.id.fabAddSimCategory)

        tvScenarioIcon  = v.findViewById(R.id.tvScenarioIcon)
        tvInputTitle    = v.findViewById(R.id.tvInputTitle)
        tvInputDesc     = v.findViewById(R.id.tvInputDesc)
        groupJobLoss    = v.findViewById(R.id.groupJobLoss)
        groupMedical    = v.findViewById(R.id.groupMedical)
        groupIncomeDrop = v.findViewById(R.id.groupIncomeDrop)
        groupInvestment = v.findViewById(R.id.groupInvestment)
        groupEmiHike    = v.findViewById(R.id.groupEmiHike)

        etJLSavings   = v.findViewById(R.id.etJLSavings)
        etJLExpenses  = v.findViewById(R.id.etJLExpenses)
        etJLEmi       = v.findViewById(R.id.etJLEmi)
        etJLFallback  = v.findViewById(R.id.etJLFallback)

        etMedSavings   = v.findViewById(R.id.etMedSavings)
        etMedInsurance = v.findViewById(R.id.etMedInsurance)
        etMedCost      = v.findViewById(R.id.etMedCost)
        etMedIncome    = v.findViewById(R.id.etMedIncome)
        etMedExpenses  = v.findViewById(R.id.etMedExpenses)

        etIDIncome    = v.findViewById(R.id.etIDIncome)
        etIDExpenses  = v.findViewById(R.id.etIDExpenses)
        etIDEmi       = v.findViewById(R.id.etIDEmi)
        etIDSavings   = v.findViewById(R.id.etIDSavings)
        seekIDDrop    = v.findViewById(R.id.seekIDDrop)
        tvIDDropLabel = v.findViewById(R.id.tvIDDropLabel)

        etInvPortfolio  = v.findViewById(R.id.etInvPortfolio)
        etInvSip        = v.findViewById(R.id.etInvSip)
        etInvReturn     = v.findViewById(R.id.etInvReturn)
        seekInvCrash    = v.findViewById(R.id.seekInvCrash)
        tvInvCrashLabel = v.findViewById(R.id.tvInvCrashLabel)

        etEmiCurrent     = v.findViewById(R.id.etEmiCurrent)
        etEmiOutstanding = v.findViewById(R.id.etEmiOutstanding)
        etEmiIncome      = v.findViewById(R.id.etEmiIncome)
        etEmiExpenses    = v.findViewById(R.id.etEmiExpenses)
        etEmiSavings     = v.findViewById(R.id.etEmiSavings)
        seekEmiHike      = v.findViewById(R.id.seekEmiHike)
        tvEmiHikeLabel   = v.findViewById(R.id.tvEmiHikeLabel)

        btnRunSim          = v.findViewById(R.id.btnRunSim)
        btnSaveSim         = v.findViewById(R.id.btnSaveSim)
        layoutResults      = v.findViewById(R.id.layoutResults)
        tvResultIcon       = v.findViewById(R.id.tvResultIcon)
        tvResultTitle      = v.findViewById(R.id.tvResultTitle)
        tvResultSub        = v.findViewById(R.id.tvResultSub)
        tvStatMonths       = v.findViewById(R.id.tvStatMonths)
        tvStatDate         = v.findViewById(R.id.tvStatDate)
        tvStatBalance      = v.findViewById(R.id.tvStatBalance)
        tvStatBalanceLabel = v.findViewById(R.id.tvStatBalanceLabel)
        simChart           = v.findViewById(R.id.simChart)
        llInsights         = v.findViewById(R.id.llInsights)

        v.findViewById<TextView>(R.id.btnSimHamburger)?.setOnClickListener {
            (requireActivity() as? com.example.team_arthsetu.MainActivity)?.openDrawer()
        }
    }

    // ── Chips ─────────────────────────────────────────────────────────────────

    private fun setupChips() {
        chipJobLoss.setOnClickListener    { selectScenario(Scenario.JOB_LOSS)    }
        chipMedical.setOnClickListener    { selectScenario(Scenario.MEDICAL)     }
        chipIncomeDrop.setOnClickListener { selectScenario(Scenario.INCOME_DROP) }
        chipInvestment.setOnClickListener { selectScenario(Scenario.INVESTMENT)  }
        chipEmiHike.setOnClickListener    { selectScenario(Scenario.EMI_HIKE)    }
        selectScenario(Scenario.JOB_LOSS)
    }

    private fun selectScenario(s: Scenario) {
        currentScenario = s
        customSaveLabel = null

        setAllChipsInactive()
        val active = when (s) {
            Scenario.JOB_LOSS    -> chipJobLoss
            Scenario.MEDICAL     -> chipMedical
            Scenario.INCOME_DROP -> chipIncomeDrop
            Scenario.INVESTMENT  -> chipInvestment
            Scenario.EMI_HIKE    -> chipEmiHike
        }
        setChipActive(active)
        applyScenarioMetaAndVisibility(s)
        layoutResults.visibility = View.GONE
    }

    private fun applyScenarioMetaAndVisibility(s: Scenario) {
        groupJobLoss.visibility    = gone(s != Scenario.JOB_LOSS)
        groupMedical.visibility    = gone(s != Scenario.MEDICAL)
        groupIncomeDrop.visibility = gone(s != Scenario.INCOME_DROP)
        groupInvestment.visibility = gone(s != Scenario.INVESTMENT)
        groupEmiHike.visibility    = gone(s != Scenario.EMI_HIKE)

        data class ScenarioMeta(val icon: String, val title: String, val desc: String)
        val meta = when (s) {
            Scenario.JOB_LOSS    -> ScenarioMeta("💼", "Job Loss Scenario",    "How long can you survive without your primary income?")
            Scenario.MEDICAL     -> ScenarioMeta("🏥", "Medical Emergency",    "Impact of a major hospital bill on your finances.")
            Scenario.INCOME_DROP -> ScenarioMeta("📉", "Income Drop",          "Salary cut or business slowdown — how long is your runway?")
            Scenario.INVESTMENT  -> ScenarioMeta("📊", "Investment Crash",     "Market crash impact on your portfolio and recovery timeline.")
            Scenario.EMI_HIKE    -> ScenarioMeta("🏦", "EMI Rate Hike",        "How a repo rate increase raises your EMI burden.")
        }
        val title = if (!customSaveLabel.isNullOrBlank()) "${meta.title} — ${customSaveLabel!!.trim()}" else meta.title
        tvScenarioIcon.text = meta.icon
        tvInputTitle.text   = title
        tvInputDesc.text    = meta.desc
    }

    private fun gone(isGone: Boolean) = if (isGone) View.GONE else View.VISIBLE

    // ── Sliders ───────────────────────────────────────────────────────────────

    private fun setupSliders() {
        // Income Drop %
        tvIDDropLabel.text = "${seekIDDrop.progress}%"
        seekIDDrop.setOnSeekBarChangeListener(sliderListener { p ->
            tvIDDropLabel.text = "${p.coerceAtLeast(10)}%"
        })

        // Investment Crash %
        tvInvCrashLabel.text = "${seekInvCrash.progress}% drop"
        seekInvCrash.setOnSeekBarChangeListener(sliderListener { p ->
            tvInvCrashLabel.text = "${p.coerceAtLeast(5)}% drop"
        })

        // EMI Rate Hike (stored as 0.1% steps → seekbar 0–60 = 0–6.0%)
        tvEmiHikeLabel.text = "+${seekEmiHike.progress / 10.0}%"
        seekEmiHike.setOnSeekBarChangeListener(sliderListener { p ->
            tvEmiHikeLabel.text = "+${p / 10.0}%"
        })
    }

    private fun sliderListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) = onChanged(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnRunSim.setOnClickListener  { runSimulation() }
        btnSaveSim.setOnClickListener { saveCurrentResult() }
    }

    private fun runSimulation() {
        when (currentScenario) {
            Scenario.JOB_LOSS    -> runJobLoss()
            Scenario.MEDICAL     -> runMedical()
            Scenario.INCOME_DROP -> runIncomeDrop()
            Scenario.INVESTMENT  -> runInvestmentCrash()
            Scenario.EMI_HIKE    -> runEmiHike()
        }
    }

    private fun runJobLoss() {
        val savings  = etJLSavings.d  ?: return toast("Enter current savings")
        val expenses = etJLExpenses.d ?: return toast("Enter monthly expenses")
        if (savings == 0.0 || expenses == 0.0) return toast("Savings and expenses must be > 0")
        simVm.runJobLoss(SimInput.JobLoss(
            savings         = savings,
            monthlyExpenses = expenses,
            emiPayments     = etJLEmi.d ?: 0.0,
            fallbackIncome  = etJLFallback.d ?: 0.0
        ))
    }

    private fun runMedical() {
        val savings  = etMedSavings.d  ?: return toast("Enter savings")
        val cost     = etMedCost.d     ?: return toast("Enter emergency cost")
        val income   = etMedIncome.d   ?: return toast("Enter monthly income")
        val expenses = etMedExpenses.d ?: return toast("Enter monthly expenses")
        simVm.runMedical(SimInput.MedicalEmergency(
            savings           = savings,
            insuranceCoverage = etMedInsurance.d ?: 0.0,
            emergencyCost     = cost,
            monthlyExpenses   = expenses,
            monthlyIncome     = income
        ))
    }

    private fun runIncomeDrop() {
        val income   = etIDIncome.d   ?: return toast("Enter current income")
        val expenses = etIDExpenses.d ?: return toast("Enter monthly expenses")
        val savings  = etIDSavings.d  ?: return toast("Enter current savings")
        simVm.runIncomeDrop(SimInput.IncomeDrop(
            currentIncome        = income,
            reducedIncomePercent = seekIDDrop.progress.coerceAtLeast(10),
            monthlyExpenses      = expenses,
            emiPayments          = etIDEmi.d ?: 0.0,
            savings              = savings
        ))
    }

    private fun runInvestmentCrash() {
        val portfolio = etInvPortfolio.d ?: return toast("Enter portfolio value")
        if (portfolio == 0.0) return toast("Portfolio value must be > 0")
        simVm.runInvestmentCrash(SimInput.InvestmentCrash(
            portfolioValue      = portfolio,
            crashPercent        = seekInvCrash.progress.coerceAtLeast(5),
            monthlyContribution = etInvSip.d ?: 0.0,
            expectedReturnPct   = etInvReturn.text?.toString()?.toIntOrNull()?.coerceIn(4, 30) ?: 12
        ))
    }

    private fun runEmiHike() {
        val currentEmi   = etEmiCurrent.d     ?: return toast("Enter current EMI")
        val outstanding  = etEmiOutstanding.d ?: return toast("Enter loan outstanding")
        val income       = etEmiIncome.d      ?: return toast("Enter monthly income")
        val expenses     = etEmiExpenses.d    ?: return toast("Enter monthly expenses")
        val savings      = etEmiSavings.d     ?: return toast("Enter current savings")
        val hikePct      = seekEmiHike.progress / 10.0   // convert 0–60 → 0.0–6.0%
        simVm.runEmiRateHike(SimInput.EmiRateHike(
            currentEmi      = currentEmi,
            loanOutstanding = outstanding,
            rateHikePercent = hikePct.coerceAtLeast(0.1),
            monthlyIncome   = income,
            monthlyExpenses = expenses,
            savings         = savings
        ))
    }

    private fun saveCurrentResult() {
        val res = simVm.result.value ?: return
        val label = customSaveLabel?.trim()?.takeIf { it.isNotEmpty() }
        val toSave = if (label != null) res.copy(scenarioType = label) else res
        simVm.saveResult(toSave, buildInputMap())
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        simVm.isLoading.observe(viewLifecycleOwner) { loading ->
            btnRunSim.isEnabled = !loading
            btnRunSim.text = if (loading) "⏳  Simulating…" else "▶  Run Simulation"
        }
        simVm.result.observe(viewLifecycleOwner) { res ->
            res ?: return@observe
            showResults(res)
        }
        simVm.isSaved.observe(viewLifecycleOwner) { saved ->
            if (saved) {
                AppSnackbar.show(requireView(), "✅ Saved to cloud")
                simVm.isSaved.value = false
            }
        }
        simVm.error.observe(viewLifecycleOwner) { err ->
            err ?: return@observe
            AppSnackbar.showLong(requireView(), "Error: $err")
            simVm.clearError()
        }
    }

    // ── Show Results ──────────────────────────────────────────────────────────

    private fun showResults(res: SimResult) {
        layoutResults.visibility = View.VISIBLE
        layoutResults.alpha = 0f
        layoutResults.animate().alpha(1f).setDuration(350).start()

        val isInvCrash = res.scenarioType == "Investment Crash"
        val scenarioLine = if (!customSaveLabel.isNullOrBlank()) {
            "${customSaveLabel!!.trim()} (${engineScenarioTitle(currentScenario)})"
        } else {
            res.scenarioType
        }

        // ── Summary ─────────────────────────────────────────────────────────
        if (res.survived) {
            tvResultIcon.text  = if (isInvCrash) "📈" else "✅"
            tvResultTitle.text = if (isInvCrash) "Portfolio Recovered!" else "Financially Stable"
            tvResultSub.text   = scenarioLine + if (isInvCrash)
                " — portfolio reaches target within simulation horizon"
            else " — survives full simulation"
        } else {
            val m = res.survivalMonths
            tvResultIcon.text  = if (isInvCrash) "⏳" else if (m <= 3) "🚨" else if (m <= 6) "⚠️" else "📅"
            tvResultTitle.text = when {
                isInvCrash   -> if (m < 0) "Recovery > 10 Years" else "$m Months to Recover"
                m <= 3       -> "CRITICAL — Very Low Runway"
                m <= 6       -> "WARNING — Short Runway"
                m <= 12      -> "Moderate Runway"
                else         -> "Adequate Runway"
            }
            tvResultSub.text = scenarioLine + " simulation"
        }

        // ── Stat boxes ───────────────────────────────────────────────────────
        val monthsLabel = if (isInvCrash) "Recovery Months" else "Months Runway"
        tvStatMonths.text = if (res.survivalMonths < 0) if (isInvCrash) "10yrs+" else "120+" else "${res.survivalMonths}"
        tvStatMonths.setTextColor(Color.parseColor(
            when {
                res.survivalMonths in 1..3  -> "#DC2626"
                res.survivalMonths in 4..6  -> "#D97706"
                res.survivalMonths in 7..12 -> "#B45309"
                else                        -> "#16A34A"
            }
        ))

        tvStatDate.text = res.depletionDate ?: if (isInvCrash) "—" else "No depletion"
        tvStatDate.setTextColor(Color.parseColor(if (res.survived) "#16A34A" else "#DC2626"))
        view?.findViewById<TextView>(R.id.tvStatMonths)
            ?.hint   // unused — just for compile check

        val finalBal = res.remainingBalance
        tvStatBalance.text = fmtBal(finalBal)
        tvStatBalance.setTextColor(Color.parseColor(if (finalBal >= 0) "#16A34A" else "#DC2626"))

        when {
            res.debtRequired > 0 -> {
                tvStatBalanceLabel.text = "Debt Required"
                tvStatBalance.text = fmtBal(res.debtRequired)
                tvStatBalance.setTextColor(Color.parseColor("#DC2626"))
            }
            res.requiredExpenseCut > 0 -> {
                tvStatBalanceLabel.text = "Cut/Month Needed"
                tvStatBalance.text = fmtBal(res.requiredExpenseCut)
                tvStatBalance.setTextColor(Color.parseColor("#D97706"))
            }
            isInvCrash -> {
                tvStatBalanceLabel.text = "Final Portfolio"
                tvStatBalance.text = fmtBal(finalBal)
            }
            else -> tvStatBalanceLabel.text = "Final Balance"
        }

        // ── Chart ─────────────────────────────────────────────────────────
        simChart.post { simChart.setData(res.balanceTrend, res.survivalMonths) }

        // ── Insights ──────────────────────────────────────────────────────
        llInsights.removeAllViews()
        res.insights.forEachIndexed { i, insight ->
            val tv = TextView(requireContext()).apply {
                text = insight; textSize = 13f
                setTextColor(Color.parseColor("#374151"))
                setPadding(0, if (i == 0) 0 else dpToPx(12), 0, 0)
                setLineSpacing(0f, 1.45f)
            }
            llInsights.addView(tv)
            if (i < res.insights.size - 1) {
                val div = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                    ).also { it.topMargin = dpToPx(12) }
                    setBackgroundColor(Color.parseColor("#F0F2F8"))
                }
                llInsights.addView(div)
            }
        }
    }

    // ── Pre-fill from cached data ─────────────────────────────────────────────

    private fun prefill() {
        val all = expenseVm.allTransactions.value ?: emptyList()
        val cal = Calendar.getInstance()
        val m   = cal.get(Calendar.MONTH); val y = cal.get(Calendar.YEAR)
        val cur = all.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == m && c.get(Calendar.YEAR) == y
        }
        val income  = cur.filter { it.type == "credit" }.sumOf { it.amount }
        val expense = cur.filter { it.type == "debit"  }.sumOf { it.amount }

        val summary  = wealthVm.summary.value
        val savings  = summary?.bankBalance         ?: 0.0
        val invested = summary?.totalAssets?.minus(savings) ?: 0.0   // approx invested assets
        val liab     = summary?.totalLiabilities    ?: 0.0
        val estEmi   = liab * 0.03   // ~3% of outstanding per month

        // Common
        if (savings > 0) {
            etJLSavings.setIfEmpty(savings.toInt().toString())
            etMedSavings.setIfEmpty(savings.toInt().toString())
            etIDSavings.setIfEmpty(savings.toInt().toString())
            etEmiSavings.setIfEmpty(savings.toInt().toString())
        }
        if (expense > 0) {
            etJLExpenses.setIfEmpty(expense.toInt().toString())
            etMedExpenses.setIfEmpty(expense.toInt().toString())
            etIDExpenses.setIfEmpty(expense.toInt().toString())
            etEmiExpenses.setIfEmpty(expense.toInt().toString())
        }
        if (income > 0) {
            etMedIncome.setIfEmpty(income.toInt().toString())
            etIDIncome.setIfEmpty(income.toInt().toString())
            etEmiIncome.setIfEmpty(income.toInt().toString())
        }
        if (estEmi > 0) {
            etJLEmi.setIfEmpty(estEmi.toInt().toString())
            etIDEmi.setIfEmpty(estEmi.toInt().toString())
            etEmiCurrent.setIfEmpty(estEmi.toInt().toString())
        }
        // Investment crash — pre-fill from total invested (Stocks + MF + Gold etc.)
        if (invested > 0) {
            etInvPortfolio.setIfEmpty(invested.toInt().toString())
        }
        // Monthly SIP estimate from expense data
        val sipEst = (income - expense).coerceAtLeast(0.0)
        if (sipEst > 0) etInvSip.setIfEmpty(sipEst.toInt().toString())

        // EMI outstanding — use total liabilities from WealthVM
        if (liab > 0) etEmiOutstanding.setIfEmpty(liab.toInt().toString())
    }

    // ── Input map for Firebase ────────────────────────────────────────────────

    private fun buildInputMap(): Map<String, Any> {
        val base: MutableMap<String, Any> = when (currentScenario) {
            Scenario.JOB_LOSS -> mutableMapOf(
                "scenario"        to "Job Loss",
                "savings"         to (etJLSavings.d  ?: 0.0),
                "monthlyExpenses" to (etJLExpenses.d ?: 0.0),
                "emiPayments"     to (etJLEmi.d      ?: 0.0),
                "fallbackIncome"  to (etJLFallback.d ?: 0.0)
            )
            Scenario.MEDICAL -> mutableMapOf(
                "scenario"          to "Medical Emergency",
                "savings"           to (etMedSavings.d   ?: 0.0),
                "insuranceCoverage" to (etMedInsurance.d ?: 0.0),
                "emergencyCost"     to (etMedCost.d      ?: 0.0),
                "monthlyIncome"     to (etMedIncome.d    ?: 0.0),
                "monthlyExpenses"   to (etMedExpenses.d  ?: 0.0)
            )
            Scenario.INCOME_DROP -> mutableMapOf(
                "scenario"             to "Income Drop",
                "currentIncome"        to (etIDIncome.d   ?: 0.0),
                "retainedIncomePercent" to seekIDDrop.progress,
                "monthlyExpenses"      to (etIDExpenses.d ?: 0.0),
                "emiPayments"          to (etIDEmi.d      ?: 0.0),
                "savings"              to (etIDSavings.d  ?: 0.0)
            )
            Scenario.INVESTMENT -> mutableMapOf(
                "scenario"          to "Investment Crash",
                "portfolioValue"    to (etInvPortfolio.d ?: 0.0),
                "crashPercent"      to seekInvCrash.progress,
                "monthlyContribution" to (etInvSip.d ?: 0.0),
                "expectedReturnPct" to (etInvReturn.text?.toString()?.toIntOrNull() ?: 12)
            )
            Scenario.EMI_HIKE -> mutableMapOf(
                "scenario"        to "EMI Rate Hike",
                "currentEmi"      to (etEmiCurrent.d     ?: 0.0),
                "loanOutstanding" to (etEmiOutstanding.d ?: 0.0),
                "rateHikePct"     to (seekEmiHike.progress / 10.0),
                "monthlyIncome"   to (etEmiIncome.d      ?: 0.0),
                "monthlyExpenses" to (etEmiExpenses.d    ?: 0.0),
                "savings"         to (etEmiSavings.d     ?: 0.0)
            )
        }
        base["engineScenarioType"] = engineScenarioTitle(currentScenario)
        customSaveLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { base["userCategoryLabel"] = it }
        return base
    }

    private fun engineScenarioTitle(s: Scenario): String = when (s) {
        Scenario.JOB_LOSS -> "Job Loss"
        Scenario.MEDICAL -> "Medical Emergency"
        Scenario.INCOME_DROP -> "Income Drop"
        Scenario.INVESTMENT -> "Investment Crash"
        Scenario.EMI_HIKE -> "EMI Rate Hike"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val TextInputEditText.d: Double?
        get() = text?.toString()?.trim()?.toDoubleOrNull()

    private fun TextInputEditText.setIfEmpty(value: String) {
        if (text.isNullOrBlank()) setText(value)
    }

    private fun toast(msg: String) = AppSnackbar.show(requireView(), msg)

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun fmtBal(v: Double) = when {
        v >= 1_00_00_000  -> "₹${String.format("%.1f", v / 1_00_00_000)}Cr"
        v <= -1_00_00_000 -> "-₹${String.format("%.1f", -v / 1_00_00_000)}Cr"
        v >= 1_00_000     -> "₹${String.format("%.1f", v / 1_00_000)}L"
        v <= -1_00_000    -> "-₹${String.format("%.1f", -v / 1_00_000)}L"
        v >= 1_000        -> "₹${(v / 1_000).toInt()}K"
        v <= -1_000       -> "-₹${(-v / 1_000).toInt()}K"
        else              -> "₹${v.toInt()}"
    }
}
