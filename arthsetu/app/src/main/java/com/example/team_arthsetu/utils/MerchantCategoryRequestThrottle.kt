package com.example.team_arthsetu.utils

import android.content.Context
import android.util.Log

/**
 * Limits CATEGORY_REQUIRED-style prompts per merchant identity ([MerchantNormalizer.looseKey]).
 *
 * After [COOLDOWN_MS], prompts are allowed again (caller only invokes when the merchant is still
 * uncategorized). [clear] resets the window when the user saves a category mapping.
 */
object MerchantCategoryRequestThrottle {

    private const val PREF_NAME = "merchant_category_req_throttle"
    private const val KEY_PREFIX = "t_"

    private const val COOLDOWN_MS = 6L * 60L * 60L * 1000L // 6 hours

    private const val TAG = "CatReqThrottle"

    private fun keyFor(merchant: String): String {
        val l = MerchantNormalizer.looseKey(merchant)
        if (l.isNotBlank()) return l
        val s = MerchantNormalizer.strictKey(merchant)
        if (s.isNotBlank()) return s
        return merchant.trim().uppercase().ifBlank { "UNKNOWN" }
    }

    /**
     * @param merchantStillUncategorized must be true (callers gate on category / queue). When false,
     * do not notify — avoids pointless repeats while the user has already categorized.
     */
    fun shouldAllow(
        context: Context,
        merchant: String,
        merchantStillUncategorized: Boolean = true
    ): Boolean {
        if (!merchantStillUncategorized) {
            Log.d(TAG, "skip merchant=${keyFor(merchant)} — already categorized")
            return false
        }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_PREFIX + keyFor(merchant), 0L)
        if (last == 0L) return true
        val elapsed = System.currentTimeMillis() - last
        val allow = elapsed >= COOLDOWN_MS
        if (!allow) {
            Log.d(TAG, "skip merchant=${keyFor(merchant)} elapsedMs=$elapsed < cooldown=$COOLDOWN_MS")
        }
        return allow
    }

    fun record(context: Context, merchant: String) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREFIX + keyFor(merchant), System.currentTimeMillis())
            .commit()
    }

    /** Call when user assigns a category so a future uncategorized txn can prompt after cooldown immediately. */
    fun clear(context: Context, merchant: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove(KEY_PREFIX + keyFor(merchant))
        val l = MerchantNormalizer.looseKey(merchant)
        val s = MerchantNormalizer.strictKey(merchant)
        if (l.isNotBlank()) prefs.remove(KEY_PREFIX + l)
        if (s.isNotBlank() && s != l) prefs.remove(KEY_PREFIX + s)
        prefs.commit()
    }
}
