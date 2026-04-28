package com.example.team_arthsetu.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline daily expense accumulator for local backup limit alerts.
 * Uses transaction id dedupe so worker retries don't double-add amounts.
 */
object LocalExpenseTotalStore {

    private const val PREF = "local_expense_totals"
    private const val KEY_DAY = "day_key"
    private const val KEY_DAILY_TOTAL = "daily_total"
    private const val KEY_PROCESSED_IDS = "processed_ids"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun dayKey(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    private fun ensureDay(ctx: Context, day: String) {
        val p = prefs(ctx)
        if (p.getString(KEY_DAY, "") != day) {
            p.edit()
                .putString(KEY_DAY, day)
                .putFloat(KEY_DAILY_TOTAL, 0f)
                .putStringSet(KEY_PROCESSED_IDS, emptySet())
                .apply()
        }
    }

    /**
     * Adds debit amount only once per transaction id and returns the day total.
     */
    fun addDebitAndGetDailyTotal(ctx: Context, transactionId: String, amount: Double, timestamp: Long): Double {
        val day = dayKey(timestamp)
        ensureDay(ctx, day)

        val p = prefs(ctx)
        val processed = p.getStringSet(KEY_PROCESSED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (processed.contains(transactionId)) {
            return p.getFloat(KEY_DAILY_TOTAL, 0f).toDouble()
        }

        val current = p.getFloat(KEY_DAILY_TOTAL, 0f).toDouble()
        val next = (current + amount).coerceAtLeast(0.0)
        processed.add(transactionId)

        p.edit()
            .putFloat(KEY_DAILY_TOTAL, next.toFloat())
            .putStringSet(KEY_PROCESSED_IDS, processed)
            .apply()

        return next
    }
}
