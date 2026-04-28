package com.example.team_arthsetu.utils

import android.content.Context

/**
 * Suppresses duplicate tray UI when a server FCM arrives shortly after a local category prompt
 * for the same merchant (same [MerchantNormalizer.looseKey]).
 */
object LocalCategoryPromptDeduper {

    private const val PREF = "local_cat_prompt_dedupe"
    private const val KEY_PREFIX = "lk_"

    /** Window in which an FCM with [notifySource] server is skipped if local just fired. */
    private const val RECENT_LOCAL_MS = 3L * 60L * 1000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun recordLocalPrompt(context: Context, merchant: String) {
        val k = MerchantNormalizer.looseKey(merchant)
        if (k.isBlank()) return
        prefs(context).edit()
            .putLong(KEY_PREFIX + k, System.currentTimeMillis())
            .apply()
    }

    /**
     * True when we should **not** show FCM notification because local prompt was recent for this merchant.
     */
    fun shouldSuppressFcmAfterLocal(context: Context, merchantLooseKey: String): Boolean {
        if (merchantLooseKey.isBlank()) return false
        val last = prefs(context).getLong(KEY_PREFIX + merchantLooseKey, 0L)
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < RECENT_LOCAL_MS
    }
}
