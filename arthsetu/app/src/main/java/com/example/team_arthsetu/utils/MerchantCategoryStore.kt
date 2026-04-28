package com.example.team_arthsetu.utils

import android.content.Context

/**
 * Persists user-chosen merchant → category mappings in SharedPreferences.
 *
 * Primary key: [MerchantNormalizer.looseKey] (letters + digits). Fallback reads use [MerchantNormalizer.strictKey]
 * and legacy raw / spaced keys.
 */
object MerchantCategoryStore {

    private const val PREF_NAME = "merchant_categories"
    private const val KEY_PREFIX = "m_"

    private fun keyLoose(merchant: String): String {
        val k = MerchantNormalizer.looseKey(merchant).lowercase()
        return if (k.isBlank()) "" else "$KEY_PREFIX$k"
    }

    private fun keyStrict(merchant: String): String {
        val k = MerchantNormalizer.strictKey(merchant).lowercase()
        return if (k.isBlank()) "" else "${KEY_PREFIX}s_$k"
    }

    private fun keyRaw(merchant: String): String =
        "$KEY_PREFIX${merchant.trim().lowercase()}"

    private fun keyNormalized(merchant: String): String =
        "$KEY_PREFIX${MerchantNormalizer.normalize(merchant).lowercase()}"

    fun getCategory(context: Context, merchant: String): String? {
        if (merchant.isBlank()) return null
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        keyLoose(merchant).takeIf { it.isNotBlank() }?.let { prefs.getString(it, null) }?.let { return it }
        keyStrict(merchant).takeIf { it.isNotBlank() }?.let { prefs.getString(it, null) }?.let { return it }
        prefs.getString(keyRaw(merchant), null)?.let { return it }
        prefs.getString(keyNormalized(merchant), null)?.let { return it }

        val incomingLoose = MerchantNormalizer.looseKey(merchant)
        if (incomingLoose.isBlank()) return null
        for ((k, v) in prefs.all) {
            if (!k.startsWith(KEY_PREFIX) || v !is String) continue
            val storedLabel = k.removePrefix(KEY_PREFIX).removePrefix("s_")
            if (MerchantNormalizer.merchantsMatchForCategory(merchant, storedLabel)) return v
        }
        return null
    }

    fun setCategory(context: Context, merchant: String, category: String) {
        if (merchant.isBlank() || category.isBlank()) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        // Cache consistency: store only canonical normalized merchant key.
        keyLoose(merchant).takeIf { it.isNotBlank() }?.let { prefs.putString(it, category) }
        // Best-effort cleanup of legacy duplicate keys.
        keyStrict(merchant).takeIf { it.isNotBlank() }?.let { prefs.remove(it) }
        prefs.remove(keyRaw(merchant))
        prefs.remove(keyNormalized(merchant))
        prefs.apply()
        MerchantCategoryRequestThrottle.clear(context, merchant)
    }

    fun getAllMappings(context: Context): Map<String, String> =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .all
            .filter { it.key.startsWith(KEY_PREFIX) && !it.key.startsWith("${KEY_PREFIX}s_") && it.value is String }
            .mapKeys { it.key.removePrefix(KEY_PREFIX) }
            .mapValues { it.value as String }
}
