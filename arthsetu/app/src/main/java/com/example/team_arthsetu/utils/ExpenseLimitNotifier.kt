package com.example.team_arthsetu.utils

import android.content.Context
import com.example.team_arthsetu.model.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Shared expense-limit evaluator used by UI and background receivers.
 * Sends limit notifications once per period-bucket to avoid spam.
 */
object ExpenseLimitNotifier {

    private const val PREF = "expense_limit_notifier_state"

    fun checkAndNotify(context: Context, all: List<Transaction>) {
        val ctx = context.applicationContext
        NotificationHelper.createChannel(ctx)

        val now = Calendar.getInstance()
        val dailySpent = all.sumDebitsFrom(startOfDay(now))
        val weeklySpent = all.sumDebitsFrom(startOfWeek(now))
        val monthlySpent = all.sumDebitsFrom(startOfMonth(now))
        val quarterlySpent = all.sumDebitsFrom(startOfQuarter(now))

        val dL = ExpenseLimitStore.getDailyLimit(ctx)
        val wL = ExpenseLimitStore.getWeeklyLimit(ctx)
        val mL = ExpenseLimitStore.getMonthlyLimit(ctx)
        val qL = ExpenseLimitStore.getQuarterlyLimit(ctx)

        notifyIfExceeded(ctx, "Daily", dailySpent, dL, dailyBucket(now))
        notifyIfExceeded(ctx, "Weekly", weeklySpent, wL, weeklyBucket(now))
        notifyIfExceeded(ctx, "Monthly", monthlySpent, mL, monthlyBucket(now))
        notifyIfExceeded(ctx, "Quarterly", quarterlySpent, qL, quarterlyBucket(now))
    }

    private fun notifyIfExceeded(
        context: Context,
        period: String,
        spent: Double,
        limit: Double,
        bucket: String
    ) {
        if (limit <= 0 || spent <= limit) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = "sent_${period.lowercase(Locale.US)}"
        if (prefs.getString(key, null) == bucket) return

        NotificationHelper.showLimitExceeded(context, period, spent, limit)
        prefs.edit().putString(key, bucket).apply()
    }

    private fun List<Transaction>.sumDebitsFrom(fromMs: Long): Double =
        filter { it.type == "debit" && it.timestamp >= fromMs }.sumOf { it.amount }

    private fun startOfDay(now: Calendar): Long =
        (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun startOfWeek(now: Calendar): Long =
        (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun startOfMonth(now: Calendar): Long =
        (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun startOfQuarter(now: Calendar): Long =
        (now.clone() as Calendar).apply {
            val qStart = (get(Calendar.MONTH) / 3) * 3
            set(Calendar.MONTH, qStart)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dailyBucket(now: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)

    private fun weeklyBucket(now: Calendar): String {
        val week = now.get(Calendar.WEEK_OF_YEAR)
        val year = now.get(Calendar.YEAR)
        return "$year-W$week"
    }

    private fun monthlyBucket(now: Calendar): String {
        val month = now.get(Calendar.MONTH) + 1
        val year = now.get(Calendar.YEAR)
        return "$year-M$month"
    }

    private fun quarterlyBucket(now: Calendar): String {
        val q = (now.get(Calendar.MONTH) / 3) + 1
        val year = now.get(Calendar.YEAR)
        return "$year-Q$q"
    }
}
