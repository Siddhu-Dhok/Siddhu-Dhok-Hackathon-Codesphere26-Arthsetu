package com.example.team_arthsetu.utils

import android.content.Context
import com.example.team_arthsetu.repository.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth

/**
 * Full categorisation pipeline with strict **debit** vs **credit** behaviour.
 *
 * - **Credit:** [CategoryClassifier.classifyCredit] only — no local/cloud merchant learning.
 * - **Debit:** [MerchantCategoryStore] → Firestore exact/partial → minimal rules → `Uncategorized`.
 */
object CategoryResolver {

    suspend fun resolve(context: Context, merchant: String, body: String, txnType: String): String {
        val type = txnType.trim().lowercase()
        if (type == Constants.CREDIT) {
            return CategoryClassifier.classifyCredit(merchant, body)
        }

        if (merchant.isBlank()) return "Uncategorized"
        val cleanedMerchant = MerchantNormalizer.normalize(merchant)

        MerchantCategoryStore.getCategory(context, merchant)?.let { return it }
        MerchantCategoryStore.getCategory(context, cleanedMerchant)?.let { return it }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            val repo = FirestoreRepository()
            repo.findLearnedMerchantCategory(merchant)?.let { cat ->
                MerchantCategoryStore.setCategory(context, merchant, cat)
                return cat
            }
            repo.findLearnedMerchantCategory(cleanedMerchant)?.let { cat ->
                MerchantCategoryStore.setCategory(context, merchant, cat)
                MerchantCategoryStore.setCategory(context, cleanedMerchant, cat)
                return cat
            }
        }

        val bodyHint = body.trim().take(500)
        return CategoryClassifier.classifyDebitMinimalRules(
            cleanedMerchant.ifBlank { merchant },
            bodyHint
        )
    }
}
