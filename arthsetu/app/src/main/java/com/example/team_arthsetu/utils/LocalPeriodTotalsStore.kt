package com.example.team_arthsetu.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

/**
 * Offline debit totals for SMS/worker backup limit checks (local timezone).
 * Dedupes by transaction id so WorkManager retries do not double-count.
 */
object LocalPeriodTotalsStore {

    private const val PREF = "local_period_totals"
    private const val KEY_DAY = "day_key"
    private const val KEY_WEEK = "week_key"
    private const val KEY_MONTH = "month_key"
    private const val KEY_QUARTER = "quarter_key"
    private const val KEY_DAILY = "daily_total"
    private const val KEY_WEEKLY = "weekly_total"
    private const val KEY_MONTHLY = "monthly_total"
    private const val KEY_QUARTERLY = "quarterly_total"
    private const val KEY_CAT_MONTH = "cat_month_key"
    private const val KEY_CAT_JSON = "cat_month_json"
    private const val KEY_PROCESSED = "processed_ids"

    data class Result(
        val dailyTotal: Double,
        val weeklyTotal: Double,
        val monthlyTotal: Double,
        val quarterlyTotal: Double,
        /** Spending this calendar month for [category] after this debit (if counted). */
        val categoryMonthSpent: Double,
        val category: String,
        /** False when [transactionId] was already applied (e.g. WorkManager retry). */
        val appliedNewDebit: Boolean
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * Adds [amount] once for [transactionId] across all period buckets that align with [timestamp].
     */
    fun addDebitAndGetTotals(
        ctx: Context,
        transactionId: String,
        amount: Double,
        timestamp: Long,
        category: String
    ): Result {
        val p = prefs(ctx)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }

        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val weekKey = weeklyBucket(cal)
        val monthKey = monthlyBucket(cal)
        val quarterKey = quarterlyBucket(cal)

        var daily = p.getFloat(KEY_DAILY, 0f).toDouble()
        var weekly = p.getFloat(KEY_WEEKLY, 0f).toDouble()
        var monthly = p.getFloat(KEY_MONTHLY, 0f).toDouble()
        var quarterly = p.getFloat(KEY_QUARTERLY, 0f).toDouble()

        if (p.getString(KEY_DAY, "") != dayKey) {
            daily = 0.0
            p.edit().putString(KEY_DAY, dayKey).putFloat(KEY_DAILY, 0f).apply()
        }
        if (p.getString(KEY_WEEK, "") != weekKey) {
            weekly = 0.0
            p.edit().putString(KEY_WEEK, weekKey).putFloat(KEY_WEEKLY, 0f).apply()
        }
        if (p.getString(KEY_MONTH, "") != monthKey) {
            monthly = 0.0
            p.edit().putString(KEY_MONTH, monthKey).putFloat(KEY_MONTHLY, 0f).apply()
        }
        if (p.getString(KEY_QUARTER, "") != quarterKey) {
            quarterly = 0.0
            p.edit().putString(KEY_QUARTER, quarterKey).putFloat(KEY_QUARTERLY, 0f).apply()
        }

        var catJson = p.getString(KEY_CAT_JSON, "{}") ?: "{}"
        if (p.getString(KEY_CAT_MONTH, "") != monthKey) {
            catJson = "{}"
            p.edit().putString(KEY_CAT_MONTH, monthKey).apply()
        }

        val processed = p.getStringSet(KEY_PROCESSED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (processed.contains(transactionId)) {
            val catMap = parseCatMap(catJson)
            val catSpent = catMap[category] ?: 0.0
            return Result(daily, weekly, monthly, quarterly, catSpent, category, appliedNewDebit = false)
        }

        val nextDaily = daily + amount
        val nextWeekly = weekly + amount
        val nextMonthly = monthly + amount
        val nextQuarterly = quarterly + amount

        val catMap = parseCatMap(catJson)
        val nextCat = (catMap[category] ?: 0.0) + amount
        catMap[category] = nextCat
        val newCatJson = toJson(catMap)

        processed.add(transactionId)
        p.edit()
            .putFloat(KEY_DAILY, nextDaily.toFloat())
            .putFloat(KEY_WEEKLY, nextWeekly.toFloat())
            .putFloat(KEY_MONTHLY, nextMonthly.toFloat())
            .putFloat(KEY_QUARTERLY, nextQuarterly.toFloat())
            .putString(KEY_CAT_JSON, newCatJson)
            .putStringSet(KEY_PROCESSED, processed)
            .apply()

        return Result(nextDaily, nextWeekly, nextMonthly, nextQuarterly, nextCat, category, appliedNewDebit = true)
    }

    private fun weeklyBucket(cal: Calendar): String {
        val c = cal.clone() as Calendar
        val week = c.get(Calendar.WEEK_OF_YEAR)
        val year = c.get(Calendar.YEAR)
        return "$year-W$week"
    }

    private fun monthlyBucket(cal: Calendar): String {
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        return "$year-M$month"
    }

    private fun quarterlyBucket(cal: Calendar): String {
        val q = (cal.get(Calendar.MONTH) / 3) + 1
        val year = cal.get(Calendar.YEAR)
        return "$year-Q$q"
    }

    private fun parseCatMap(json: String): MutableMap<String, Double> {
        val out = mutableMapOf<String, Double>()
        try {
            val o = JSONObject(json)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optDouble(k, 0.0)
            }
        } catch (_: Exception) { }
        return out
    }

    private fun toJson(map: Map<String, Double>): String {
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, v)
        }
        return o.toString()
    }
}
