package com.example.team_arthsetu.utils

import android.content.Context
import com.example.team_arthsetu.model.Transaction

/**
 * After a bank SMS is parsed and a new debit row is written to Firestore (`users/{uid}/transactions`):
 *
 * - **Needs user category** (uncategorized / unknown merchant): [CategoryRequiredNotifier] enqueues
 *   learning + shows the rich “choose category” notification (foreground or background).
 * - **Already categorized** (rules / learned mapping): shows a compact “expense saved” notification
 *   so the user sees confirmation when the app is closed or open.
 */
object SmsPipelineNotifications {

    fun afterDebitSavedFromSms(context: Context, txn: Transaction) {
        if (!txn.type.equals(Constants.DEBIT, ignoreCase = true)) return
        if (CategoryClassifier.needsDebitUserCategorization(txn.merchant, txn.category)) {
            CategoryRequiredNotifier.trigger(context, txn)
            return
        }
        if (!TxnNotificationTracker.shouldShowNotification(context, txn.id)) return
        TransactionNotificationHelper.showExpenseLoggedFromSms(context, txn)
        TxnNotificationTracker.markNotificationShown(context, txn.id)
    }
}
