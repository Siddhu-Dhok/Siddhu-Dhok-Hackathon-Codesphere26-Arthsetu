package com.example.team_arthsetu.utils

import android.content.Context
import android.util.Log
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single entry for "needs category" side effects: Firestore request queue (→ FCM) + local notification,
 * with per-merchant throttling.
 */
object CategoryRequiredNotifier {

    private const val TAG = "CatRequiredNotifier"

    fun trigger(context: Context, txn: Transaction) {
        if (!txn.type.equals(Constants.DEBIT, ignoreCase = true)) return
        if (!CategoryClassifier.needsDebitUserCategorization(txn.merchant, txn.category)) return

        val app = context.applicationContext
        if (!MerchantCategoryRequestThrottle.shouldAllow(
                app,
                txn.merchant,
                merchantStillUncategorized = true
            )
        ) {
            return
        }
        MerchantCategoryRequestThrottle.record(app, txn.merchant)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirestoreRepository().enqueueMerchantCategoryLearningRequest(txn)
            } catch (e: Exception) {
                Log.w(TAG, "enqueueMerchantCategoryLearningRequest failed", e)
            }
        }

        if (TxnNotificationTracker.shouldShowNotification(app, txn.id)) {
            TransactionNotificationHelper.ensureChannel(app)
            TransactionNotificationHelper.showCategorizeNotification(app, txn)
            TxnNotificationTracker.markNotificationShown(app, txn.id)
            LocalCategoryPromptDeduper.recordLocalPrompt(app, txn.merchant)
        }
    }
}
