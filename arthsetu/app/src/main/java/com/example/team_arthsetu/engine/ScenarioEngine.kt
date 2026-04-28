package com.example.team_arthsetu.engine

import com.example.team_arthsetu.model.MonthPoint
import com.example.team_arthsetu.model.SimInput
import com.example.team_arthsetu.model.SimResult
import com.example.team_arthsetu.model.depletionDateFor

/**
 * Pure Kotlin simulation engine — no Android dependencies.
 * All scenarios simulate month-by-month until depletion or the 10-year cap.
 */
object ScenarioEngine {

    private const val MAX_MONTHS = 120   // 10-year cap

    // ── Feature 1: Job Loss ───────────────────────────────────────────────────

    fun simulateJobLoss(input: SimInput.JobLoss): SimResult {
        val monthlyBurn = input.monthlyExpenses + input.emiPayments - input.fallbackIncome
        val trend       = mutableListOf<MonthPoint>()
        var balance     = input.savings
        var month       = 0

        trend.add(MonthPoint(0, balance))

        while (month < MAX_MONTHS) {
            month++
            balance -= monthlyBurn
            trend.add(MonthPoint(month, balance))
            if (balance <= 0) break
        }

        val depleted      = balance <= 0
        val survivalMonths = if (depleted) month else -1

        return SimResult(
            scenarioType   = "Job Loss",
            survivalMonths = survivalMonths,
            depletionDate  = if (depleted) depletionDateFor(month) else null,
            balanceTrend   = trend,
            insights       = InsightsEngine.forJobLoss(input, survivalMonths, monthlyBurn),
            remainingBalance = trend.last().balance
        )
    }

    // ── Feature 2: Medical Emergency ──────────────────────────────────────────

    fun simulateMedical(input: SimInput.MedicalEmergency): SimResult {
        val netCost      = (input.emergencyCost - input.insuranceCoverage).coerceAtLeast(0.0)
        val afterShock   = input.savings - netCost
        val debtRequired = if (afterShock < 0) -afterShock else 0.0
        val startBalance = afterShock.coerceAtLeast(-netCost)  // allow negative start

        val trend    = mutableListOf<MonthPoint>()
        var balance  = startBalance
        var month    = 0
        val monthly  = input.monthlyIncome - input.monthlyExpenses  // ongoing net cash flow

        trend.add(MonthPoint(0, balance))

        // Simulate up to MAX_MONTHS or until fully recovered (or depleted)
        while (month < MAX_MONTHS) {
            month++
            balance += monthly
            trend.add(MonthPoint(month, balance))
            // Stop if recovered to original savings (forward sim done) OR depleted further
            if (balance >= input.savings || (monthly < 0 && balance <= -input.savings * 2)) break
        }

        val depleted       = trend.any { it.balance <= 0 } && monthly < 0
        val depletionMonth = if (depleted) trend.indexOfFirst { it.balance <= 0 } else -1

        return SimResult(
            scenarioType   = "Medical Emergency",
            survivalMonths = depletionMonth,
            depletionDate  = if (depleted) depletionDateFor(depletionMonth) else null,
            balanceTrend   = trend,
            insights       = InsightsEngine.forMedical(input, debtRequired, netCost),
            debtRequired   = debtRequired,
            remainingBalance = trend.last().balance
        )
    }

    // ── Feature 4: Investment Portfolio Crash ─────────────────────────────────

    fun simulateInvestmentCrash(input: SimInput.InvestmentCrash): SimResult {
        val crashLoss    = input.portfolioValue * (input.crashPercent / 100.0)
        val afterCrash   = input.portfolioValue - crashLoss
        val monthlyRet   = (input.expectedReturnPct / 100.0) / 12.0
        val contribution = input.monthlyContribution

        val trend   = mutableListOf<MonthPoint>()
        var balance = afterCrash
        var month   = 0

        trend.add(MonthPoint(0, balance))

        // Simulate until portfolio recovers to original value or hits MAX_MONTHS
        while (balance < input.portfolioValue && month < MAX_MONTHS) {
            month++
            balance = balance * (1 + monthlyRet) + contribution
            trend.add(MonthPoint(month, balance))
        }

        val recovered      = balance >= input.portfolioValue
        val recoveryMonths = if (recovered) month else -1

        return SimResult(
            scenarioType     = "Investment Crash",
            survivalMonths   = recoveryMonths,
            depletionDate    = if (recovered) depletionDateFor(month) else null,
            balanceTrend     = trend,
            insights         = InsightsEngine.forInvestmentCrash(input, crashLoss, recoveryMonths),
            remainingBalance = balance
        )
    }

    // ── Feature 5: EMI Rate Hike ──────────────────────────────────────────────

    fun simulateEmiRateHike(input: SimInput.EmiRateHike): SimResult {
        // Extra interest on outstanding loan due to rate hike
        val extraMonthly   = input.loanOutstanding * (input.rateHikePercent / 100.0) / 12.0
        val newEmi         = input.currentEmi + extraMonthly
        val netMonthly     = input.monthlyIncome - input.monthlyExpenses - newEmi
        val requiredCut    = if (netMonthly < 0) -netMonthly else 0.0

        val trend   = mutableListOf<MonthPoint>()
        var balance = input.savings
        var month   = 0

        trend.add(MonthPoint(0, balance))

        while (month < MAX_MONTHS) {
            month++
            balance += netMonthly
            trend.add(MonthPoint(month, balance))
            if (balance <= 0) break
            if (netMonthly >= 0 && month >= 24) break
        }

        val depleted       = balance <= 0
        val survivalMonths = if (depleted) month else -1

        return SimResult(
            scenarioType       = "EMI Rate Hike",
            survivalMonths     = survivalMonths,
            depletionDate      = if (depleted) depletionDateFor(month) else null,
            balanceTrend       = trend,
            insights           = InsightsEngine.forEmiRateHike(input, extraMonthly, newEmi, survivalMonths, requiredCut),
            requiredExpenseCut = requiredCut,
            remainingBalance   = balance
        )
    }

    // ── Feature 3: Income Drop ────────────────────────────────────────────────

    fun simulateIncomeDrop(input: SimInput.IncomeDrop): SimResult {
        val reducedIncome  = input.currentIncome * (input.reducedIncomePercent / 100.0)
        val netMonthly     = reducedIncome - input.monthlyExpenses - input.emiPayments
        val requiredCut    = if (netMonthly < 0) -netMonthly else 0.0

        val trend   = mutableListOf<MonthPoint>()
        var balance = input.savings
        var month   = 0

        trend.add(MonthPoint(0, balance))

        while (month < MAX_MONTHS) {
            month++
            balance += netMonthly
            trend.add(MonthPoint(month, balance))
            if (balance <= 0) break
            if (netMonthly >= 0 && month >= 24) break  // stable — show 2 years and stop
        }

        val depleted       = balance <= 0
        val survivalMonths = if (depleted) month else -1

        return SimResult(
            scenarioType      = "Income Drop",
            survivalMonths    = survivalMonths,
            depletionDate     = if (depleted) depletionDateFor(month) else null,
            balanceTrend      = trend,
            insights          = InsightsEngine.forIncomeDrop(input, survivalMonths, requiredCut, reducedIncome),
            requiredExpenseCut = requiredCut,
            remainingBalance  = trend.last().balance
        )
    }
}
