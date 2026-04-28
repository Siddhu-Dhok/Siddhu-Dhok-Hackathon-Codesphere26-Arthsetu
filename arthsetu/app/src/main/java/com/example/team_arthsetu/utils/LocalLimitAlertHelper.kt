package com.example.team_arthsetu.utils

import android.content.Context

/**
 * SMS/worker backup: after a parsed debit, compare local running totals to limits and notify.
 * Notifies only when this debit was newly applied (not a duplicate id) to avoid retry spam.
 */
object LocalLimitAlertHelper {

    fun checkAfterDebit(
        ctx: Context,
        transactionId: String,
        amount: Double,
        timestamp: Long,
        category: String
    ) {
        val app = ctx.applicationContext
        NotificationHelper.createChannel(app)

        val totals = LocalPeriodTotalsStore.addDebitAndGetTotals(
            app,
            transactionId,
            amount,
            timestamp,
            category
        )

        if (!totals.appliedNewDebit) return

        val dL = ExpenseLimitStore.getDailyLimit(app)
        val wL = ExpenseLimitStore.getWeeklyLimit(app)
        val mL = ExpenseLimitStore.getMonthlyLimit(app)
        val qL = ExpenseLimitStore.getQuarterlyLimit(app)

        // Match Cloud Functions: alert at >= limit (not only strict >).
        if (dL > 0 && totals.dailyTotal >= dL) {
            NotificationHelper.showLimitExceeded(app, "Daily", totals.dailyTotal, dL)
        }
        if (wL > 0 && totals.weeklyTotal >= wL) {
            NotificationHelper.showLimitExceeded(app, "Weekly", totals.weeklyTotal, wL)
        }
        if (mL > 0 && totals.monthlyTotal >= mL) {
            NotificationHelper.showLimitExceeded(app, "Monthly", totals.monthlyTotal, mL)
        }
        if (qL > 0 && totals.quarterlyTotal >= qL) {
            NotificationHelper.showLimitExceeded(app, "Quarterly", totals.quarterlyTotal, qL)
        }

        val catLimit = CategoryLimitStore.getLimit(app, category)
        if (catLimit > 0 && totals.categoryMonthSpent >= catLimit) {
            NotificationHelper.showCategoryLimitExceeded(
                app,
                category,
                totals.categoryMonthSpent,
                catLimit,
                amount
            )
        }
    }
}
