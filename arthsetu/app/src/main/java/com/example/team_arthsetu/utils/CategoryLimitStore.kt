package com.example.team_arthsetu.utils

import android.content.Context

/**
 * Persists per-category monthly spend limits in SharedPreferences.
 * Key format:  "limit_Food", "limit_Transport", …
 * Value:       Float (0 = no limit set)
 */
object CategoryLimitStore {

    private const val PREF_NAME   = "category_limits"
    private const val KEY_PREFIX  = "limit_"

    /** Returns the limit for [category], or 0.0 if none is set. */
    fun getLimit(context: Context, category: String): Double {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat("$KEY_PREFIX$category", 0f).toDouble()
    }

    /** Saves a monthly limit for [category]. Pass 0 to clear. */
    fun setLimit(context: Context, category: String, limit: Double) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("$KEY_PREFIX$category", limit.toFloat())
            .apply()
    }

    /** Removes the limit for [category]. */
    fun clearLimit(context: Context, category: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$KEY_PREFIX$category")
            .apply()
    }

    /** Returns a map of all stored limits: category → limit amount. */
    fun getAllLimits(context: Context): Map<String, Double> =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .all
            .filter { it.key.startsWith(KEY_PREFIX) && (it.value as? Float ?: 0f) > 0f }
            .mapKeys { it.key.removePrefix(KEY_PREFIX) }
            .mapValues { (it.value as Float).toDouble() }
}
