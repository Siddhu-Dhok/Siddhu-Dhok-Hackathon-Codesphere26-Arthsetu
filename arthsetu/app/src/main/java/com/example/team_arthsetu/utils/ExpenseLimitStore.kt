package com.example.team_arthsetu.utils

import android.content.Context

object ExpenseLimitStore {

    private const val PREF = "expense_limits"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setDailyLimit(ctx: Context, amount: Double) =
        prefs(ctx).edit().putFloat("daily", amount.toFloat()).apply()

    fun setWeeklyLimit(ctx: Context, amount: Double) =
        prefs(ctx).edit().putFloat("weekly", amount.toFloat()).apply()

    fun setMonthlyLimit(ctx: Context, amount: Double) =
        prefs(ctx).edit().putFloat("monthly", amount.toFloat()).apply()

    fun setQuarterlyLimit(ctx: Context, amount: Double) =
        prefs(ctx).edit().putFloat("quarterly", amount.toFloat()).apply()

    // ── Getters  (0.0 = not set) ─────────────────────────────────────────────

    fun getDailyLimit(ctx: Context): Double     = prefs(ctx).getFloat("daily",     0f).toDouble()
    fun getWeeklyLimit(ctx: Context): Double    = prefs(ctx).getFloat("weekly",    0f).toDouble()
    fun getMonthlyLimit(ctx: Context): Double   = prefs(ctx).getFloat("monthly",   0f).toDouble()
    fun getQuarterlyLimit(ctx: Context): Double = prefs(ctx).getFloat("quarterly", 0f).toDouble()

    fun clearAll(ctx: Context) = prefs(ctx).edit().clear().apply()
}
