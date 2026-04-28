package com.example.team_arthsetu.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.MerchantCategoryStore
import com.example.team_arthsetu.utils.TransactionNotificationHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles category choice from transaction notifications (action buttons or RemoteInput).
 * Updates Firestore, saves merchant→category mapping, and dismisses the notification.
 */
class TransactionCategoryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (FirebaseAuth.getInstance().currentUser == null) return@launch

                val txnId = intent.getStringExtra(EXTRA_TXN_ID) ?: return@launch
                val merchant = intent.getStringExtra(EXTRA_MERCHANT) ?: ""
                val txnType = intent.getStringExtra(EXTRA_TXN_TYPE) ?: Constants.DEBIT

                var category = intent.getStringExtra(EXTRA_CATEGORY)?.trim()
                if (category.isNullOrEmpty()) {
                    val results = RemoteInput.getResultsFromIntent(intent)
                    category = results?.getCharSequence(TransactionNotificationHelper.REMOTE_INPUT_KEY)
                        ?.toString()?.trim()
                }
                if (category.isNullOrEmpty()) return@launch

                val repo = FirestoreRepository()
                repo.updateTransactionCategory(txnId, category)
                repo.saveMerchantCategoryMapping(merchant, category)
                MerchantCategoryStore.setCategory(app, merchant, category)

                NotificationManagerCompat.from(app)
                    .cancel(TransactionNotificationHelper.notificationIdFor(txnId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_MERCHANT = "merchant"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_TXN_TYPE = "txn_type"
    }
}
