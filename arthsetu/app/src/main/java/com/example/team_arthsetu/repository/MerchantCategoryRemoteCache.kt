package com.example.team_arthsetu.repository

/**
 * In-memory cache of Firestore merchant→category rows for partial matching.
 * Invalidated when the user saves a new mapping.
 */
object MerchantCategoryRemoteCache {

    private val mappingsByUser = mutableMapOf<String, List<Pair<String, String>>>()

    fun getCached(userId: String): List<Pair<String, String>>? =
        mappingsByUser[userId]

    fun setCached(userId: String, rows: List<Pair<String, String>>) {
        mappingsByUser[userId] = rows
    }

    fun invalidate(userId: String) {
        mappingsByUser.remove(userId)
    }
}
