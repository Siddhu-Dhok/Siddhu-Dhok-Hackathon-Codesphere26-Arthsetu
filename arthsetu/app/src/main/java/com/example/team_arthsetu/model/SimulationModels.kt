package com.example.team_arthsetu.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ── Scenario input types ───────────────────────────────────────────────────────

sealed class SimInput {

    data class JobLoss(
        val savings        : Double,
        val monthlyExpenses: Double,
        val emiPayments    : Double,
        val fallbackIncome : Double = 0.0
    ) : SimInput()

    data class MedicalEmergency(
        val savings           : Double,
        val insuranceCoverage : Double,
        val emergencyCost     : Double,
        val monthlyExpenses   : Double,
        val monthlyIncome     : Double
    ) : SimInput()

    data class IncomeDrop(
        val currentIncome      : Double,
        val reducedIncomePercent: Int,    // e.g. 60 means income drops to 60% of current
        val monthlyExpenses    : Double,
        val emiPayments        : Double,
        val savings            : Double
    ) : SimInput()

    /** Feature 4: Market crash on investment portfolio (pre-fill from WealthVM) */
    data class InvestmentCrash(
        val portfolioValue      : Double,   // current invested amount (stocks + MF + etc.)
        val crashPercent        : Int,      // e.g. 30 = 30% drop in portfolio value
        val monthlyContribution : Double,   // ongoing SIP / monthly investment
        val expectedReturnPct   : Int       // e.g. 12 = 12% p.a. expected return during recovery
    ) : SimInput()

    /** Feature 5: EMI burden from interest-rate hike */
    data class EmiRateHike(
        val currentEmi         : Double,
        val loanOutstanding    : Double,    // pre-fill from WealthVM totalLiabilities
        val rateHikePercent    : Double,    // e.g. 2.5 = RBI raises repo rate by 2.5%
        val monthlyIncome      : Double,
        val monthlyExpenses    : Double,
        val savings            : Double
    ) : SimInput()
}

// ── Per-month data point for chart ────────────────────────────────────────────

data class MonthPoint(val month: Int, val balance: Double)

// ── Simulation result ─────────────────────────────────────────────────────────

data class SimResult(
    val scenarioType         : String,
    val survivalMonths       : Int,          // -1 = survives 10-year cap
    val depletionDate        : String?,      // null if no depletion
    val balanceTrend         : List<MonthPoint>,
    val insights             : List<String>,
    val debtRequired         : Double = 0.0,
    val requiredExpenseCut   : Double = 0.0,
    val remainingBalance     : Double = 0.0,
    val timestamp            : Long   = System.currentTimeMillis()
) {
    val survived: Boolean get() = survivalMonths < 0
}

// ── Firestore-friendly flat record ────────────────────────────────────────────

data class SimulationRecord(
    val id             : String             = "",
    val userId         : String             = "",
    val scenarioType   : String             = "",
    val inputs         : Map<String, Any>   = emptyMap(),
    val survivalMonths : Int                = 0,
    val survived       : Boolean            = false,
    val depletionDate  : String             = "",
    val debtRequired   : Double             = 0.0,
    val expenseCutNeeded: Double            = 0.0,
    val insights       : List<String>       = emptyList(),
    val timestamp      : Long               = System.currentTimeMillis()
)

// ── Helper: add months and format ─────────────────────────────────────────────

fun depletionDateFor(months: Int): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MONTH, months)
    return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
}

/** Fixed list of scenario kinds (matches [ScenarioEngine] `scenarioType` strings). */
object SimScenarioCatalog {
    data class Entry(
        val scenarioType: String,
        val title: String,
        val emoji: String,
        val subtitle: String
    )

    val ENTRIES: List<Entry> = listOf(
        Entry("Job Loss", "Job loss", "💼", "Survive without primary income"),
        Entry("Medical Emergency", "Medical emergency", "🏥", "Hospital bill & insurance gap"),
        Entry("Income Drop", "Income drop", "📉", "Salary cut or slowdown"),
        Entry("Investment Crash", "Investment crash", "📊", "Market shock & recovery"),
        Entry("EMI Rate Hike", "EMI rate hike", "🏦", "Higher EMI burden")
    )
}
