package com.example.team_arthsetu.utils

import com.example.team_arthsetu.model.Transaction

/**
 * Simple rule-based “AI” insights for hackathon demos.
 */
object InsightGenerator {

    fun generate(
        transactions: List<Transaction>,
        risk: RiskCalculator.Result,
        wealth: RiskCalculator.WealthInputs? = null
    ): List<String> {
        val insights = mutableListOf<String>()
        val debitTotal = risk.totalExpense
        val food = transactions
            .filter { it.isDebit() && it.category.equals("Food", ignoreCase = true) }
            .sumOf { it.amount }
        val shopping = transactions
            .filter { it.isDebit() && it.category.equals("Shopping", ignoreCase = true) }
            .sumOf { it.amount }
        val bills = transactions
            .filter { it.isDebit() && it.category.equals("Bills", ignoreCase = true) }
            .sumOf { it.amount }

        if (debitTotal > 0 && food / debitTotal >= 0.25) {
            insights += "High food spending"
        }
        if (risk.savings < 0 || (risk.totalIncome > 0 && risk.savings / risk.totalIncome < 0.05)) {
            insights += "Low savings"
        }
        if (risk.totalIncome > 0 && risk.totalExpense > risk.totalIncome) {
            insights += "Spending exceeds income"
        }
        if (debitTotal > 0 && bills / debitTotal >= 0.40) {
            insights += "High bill payments"
        }
        if (debitTotal > 0 && shopping / debitTotal >= 0.30) {
            insights += "High shopping spend"
        }
        if (wealth != null) {
            val a = wealth.totalAssets.coerceAtLeast(0.0)
            val l = wealth.totalLiabilities.coerceAtLeast(0.0)
            if (l > 0 && a >= 1.0 && l / a >= 0.5) {
                insights += "Debt is a large share of assets — risk is elevated"
            }
            if (wealth.netWorth < -1.0) {
                insights += "Negative net worth adds to financial risk"
            }
            if (l > 0 && a < 1.0) {
                insights += "Liabilities with little recorded asset cover"
            }
        }
        if (insights.isEmpty()) {
            insights += "Spending looks balanced for your data so far"
        }
        return insights.distinct()
    }
}
