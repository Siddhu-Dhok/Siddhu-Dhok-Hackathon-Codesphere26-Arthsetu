package com.example.team_arthsetu.utils

import com.example.team_arthsetu.model.Transaction
import java.util.Calendar

/**
 * Financial risk on a 0–100 scale. Cashflow is judged on a **recent** window so it matches
 * what users see on the dashboard (this month’s income, expenses, savings), not lifetime
 * totals that can stay skewed from old data. All-time is only used when there is no recent activity.
 *
 * Optional [wealth] can **raise** risk (high debt / negative net worth) or **lower** it when
 * net worth is positive and leverage is moderate.
 */
object RiskCalculator {

    data class WealthInputs(
        val netWorth: Double,
        val totalAssets: Double,
        val totalLiabilities: Double
    )

    private data class PeriodTotals(
        val expense: Double,
        val income: Double,
        val periodTransactions: List<Transaction>
    )

    data class Result(
        val totalExpense: Double,
        val totalIncome: Double,
        val savings: Double,
        val riskScore: Double,
        val riskLevel: String,
        /** 1.0 if low-savings term applies, else 0.0 */
        val lowSavingsFactor: Double,
        /** 1.0 if high-spending term applies, else 0.0 */
        val highSpendingFactor: Double
    )

    const val LEVEL_LOW = "Low"
    const val LEVEL_MEDIUM = "Medium"
    const val LEVEL_HIGH = "High"

    private val ms30d: Long = 30L * 24L * 60L * 60L * 1000L

    private fun transactionsInCalendarMonth(transactions: List<Transaction>, month: Int, year: Int): List<Transaction> =
        transactions.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year
        }

    private fun transactionsLast30Days(transactions: List<Transaction>, nowMs: Long): List<Transaction> =
        transactions.filter { it.timestamp >= nowMs - ms30d }

    /**
     * Prefer **current calendar month** when it has credited income (matches dashboard savings %).
     * If the month has spending but no credits yet, use **last 30 days**.
     * If the month is empty, use **last 30 days** when possible, else **all-time**.
     */
    private fun selectPeriodForRisk(transactions: List<Transaction>, nowMs: Long): PeriodTotals {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val monthTxns = transactionsInCalendarMonth(transactions, month, year)
        val mInc = monthTxns.filter { it.isCredit() }.sumOf { it.amount }
        val mExp = monthTxns.filter { it.isDebit() }.sumOf { it.amount }

        if (mInc > 0.0) {
            return PeriodTotals(mExp, mInc, monthTxns)
        }
        if (mExp > 0.0) {
            val t30 = transactionsLast30Days(transactions, nowMs)
            val i30 = t30.filter { it.isCredit() }.sumOf { it.amount }
            val e30 = t30.filter { it.isDebit() }.sumOf { it.amount }
            if (i30 > 0.0 || e30 > 0.0) {
                return PeriodTotals(e30, i30, t30)
            }
        }

        val t30 = transactionsLast30Days(transactions, nowMs)
        val i30 = t30.filter { it.isCredit() }.sumOf { it.amount }
        val e30 = t30.filter { it.isDebit() }.sumOf { it.amount }
        if (i30 > 0.0 || e30 > 0.0) {
            return PeriodTotals(e30, i30, t30)
        }

        val allExp = transactions.filter { it.isDebit() }.sumOf { it.amount }
        val allInc = transactions.filter { it.isCredit() }.sumOf { it.amount }
        return PeriodTotals(allExp, allInc, transactions)
    }

    private fun wealthBoost(w: WealthInputs): Double {
        val assets = w.totalAssets.coerceAtLeast(0.0)
        val liab = w.totalLiabilities.coerceAtLeast(0.0)
        var boost = 0.0
        if (liab > 0.0) {
            boost += if (assets < 1.0) {
                18.0
            } else {
                val ratio = (liab / assets).coerceIn(0.0, 2.5)
                (ratio / 2.5) * 16.0
            }
        }
        if (w.netWorth < -1.0) boost += 8.0
        return boost.coerceIn(0.0, 24.0)
    }

    /** Lowers score when net worth is solid and leverage is not extreme. */
    private fun wealthCushion(w: WealthInputs): Double {
        if (w.netWorth <= 1.0) return 0.0
        val assets = w.totalAssets.coerceAtLeast(0.0)
        val liab = w.totalLiabilities.coerceAtLeast(0.0)
        val dta = when {
            assets >= 1.0 -> liab / assets
            liab > 0.0 -> 0.95
            else -> 0.0
        }
        if (dta > 0.50) return 0.0
        val fromNw = 18.0 * (kotlin.math.min(w.netWorth, 1_200_000.0) / 1_200_000.0).coerceIn(0.0, 1.0)
        val debtEase = (1.0 - dta / 0.50).coerceIn(0.0, 1.0)
        return (fromNw * debtEase).coerceIn(0.0, 18.0)
    }

    fun compute(
        transactions: List<Transaction>,
        wealth: WealthInputs? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val period = selectPeriodForRisk(transactions, nowMs)
        val totalExpense = period.expense
        val totalIncome = period.income
        val savings = totalIncome - totalExpense

        val incomeForRatio = totalIncome.coerceAtLeast(1.0)
        val expenseRatio = (totalExpense / incomeForRatio).coerceIn(0.0, 2.0)
        val termExpense = (expenseRatio * 50.0).coerceIn(0.0, 50.0)

        val lowSavings = if (totalIncome <= 0) {
            totalExpense > 0
        } else {
            val rate = savings / totalIncome
            rate < 0.10
        }
        val lowSavingsFactor = if (lowSavings) 1.0 else 0.0
        val termLowSavings = lowSavingsFactor * 30.0

        val foodDebit = period.periodTransactions
            .filter { it.isDebit() && it.category.equals("Food", ignoreCase = true) }
            .sumOf { it.amount }
        val highSpending = (totalIncome > 0 && totalExpense / totalIncome > 0.85) ||
            (totalExpense > 0 && foodDebit / totalExpense > 0.35)
        val highSpendingFactor = if (highSpending) 1.0 else 0.0
        val termHighSpending = highSpendingFactor * 20.0

        val baseScore = (termExpense + termLowSavings + termHighSpending).coerceIn(0.0, 100.0)
        val debtExtra = wealth?.let { wealthBoost(it) } ?: 0.0
        val cushion = wealth?.let { wealthCushion(it) } ?: 0.0
        val riskScore = (baseScore + debtExtra - cushion).coerceIn(0.0, 100.0)
        val riskLevel = when {
            riskScore < 40.0 -> LEVEL_LOW
            riskScore < 70.0 -> LEVEL_MEDIUM
            else -> LEVEL_HIGH
        }

        return Result(
            totalExpense = totalExpense,
            totalIncome = totalIncome,
            savings = savings,
            riskScore = riskScore,
            riskLevel = riskLevel,
            lowSavingsFactor = lowSavingsFactor,
            highSpendingFactor = highSpendingFactor
        )
    }
}
