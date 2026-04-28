package com.example.team_arthsetu.model

/**
 * Document in `users/{uid}/merchant_categories/{docId}`.
 * [merchantName] is normalized uppercase ([com.example.simple_idea_fin.utils.MerchantNormalizer.normalize]).
 */
data class MerchantCategoryMapping(
    val merchantName: String = "",
    val category: String = "",
    val userId: String = ""
)
