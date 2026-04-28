package com.example.team_arthsetu.fcm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.utils.CategoryRequiredNotifier
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.LocalCategoryPromptDeduper
import com.example.team_arthsetu.utils.MerchantNormalizer
import com.example.team_arthsetu.utils.MerchantCategoryRequestThrottle
import com.example.team_arthsetu.ui.CategorySelectionActivity
import com.example.team_arthsetu.ui.MerchantCategoryActivity
import com.example.team_arthsetu.utils.TransactionNotificationHelper

/**
 * 1. Enqueues a Firestore doc under [com.example.simple_idea_fin.utils.Constants.SUB_MERCHANT_CATEGORY_REQUESTS]
 *    so a Cloud Function can send an FCM with [FCM_TYPE].
 * 2. Handles incoming FCM **data** messages with `type=[FCM_TYPE]` and opens [MerchantCategoryActivity].
 */
object MerchantCategoryFcmTrigger {

    const val FCM_TYPE = "merchant_category"
    private const val FCM_TYPE_UPPER = "MERCHANT_CATEGORY"

    /** Server may send this alias; same handling as [FCM_TYPE]. */
    const val FCM_TYPE_CATEGORY_REQUIRED = "CATEGORY_REQUIRED"

    private const val TAG = "MerchantCatFcm"

    fun enqueueLearningRequest(context: Context, txn: Transaction) {
        CategoryRequiredNotifier.trigger(context.applicationContext, txn)
    }

    /**
     * Expected data keys: `type=[FCM_TYPE]`, `txnId`, `merchant`, `amount`, `txnType`, optional `title`, `body`.
     */
    fun handleIncomingDataMessage(context: Context, data: Map<String, String>) {
        val type = data["type"] ?: return
        val isMerchantCat = type.equals(FCM_TYPE, ignoreCase = true) ||
            type.equals(FCM_TYPE_UPPER, ignoreCase = true) ||
            type.equals(FCM_TYPE_CATEGORY_REQUIRED, ignoreCase = true)
        if (!isMerchantCat) return
        val txnId = data["transactionId"] ?: data["txnId"] ?: return
        val merchant = data["merchant"] ?: ""
        val amount = data["amount"]?.toDoubleOrNull() ?: 0.0
        val txnType = data["txnType"] ?: "debit"
        if (!txnType.equals(Constants.DEBIT, ignoreCase = true)) return
        val title = data["title"] ?: "New Merchant Detected"
        val body = data["body"] ?: "Categorize your recent transaction"
        val app = context.applicationContext
        val notifySource = data["notifySource"]?.trim()?.lowercase().orEmpty()
        val looseFromPayload = data["merchantLooseKey"]?.trim().orEmpty()
            .ifEmpty { MerchantNormalizer.looseKey(merchant) }
        if ((notifySource.isEmpty() || notifySource == "server") &&
            LocalCategoryPromptDeduper.shouldSuppressFcmAfterLocal(app, looseFromPayload)
        ) {
            Log.d(TAG, "FCM suppressed — local prompt recent looseKey=$looseFromPayload source=$notifySource")
            return
        }
        if (!MerchantCategoryRequestThrottle.shouldAllow(
                app,
                merchant,
                merchantStillUncategorized = true
            )
        ) {
            Log.d(TAG, "FCM CATEGORY_REQUIRED throttled for merchant=$merchant")
            return
        }
        MerchantCategoryRequestThrottle.record(app, merchant)
        showFcmNotification(app, txnId, merchant, amount, txnType, title, body)
    }

    private fun showFcmNotification(
        app: Context,
        txnId: String,
        merchant: String,
        amount: Double,
        txnType: String,
        title: String,
        body: String
    ) {
        TransactionNotificationHelper.ensureChannel(app)
        val intent = Intent(app, CategorySelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CategorySelectionActivity.EXTRA_TRANSACTION_ID, txnId)
            putExtra(CategorySelectionActivity.EXTRA_TXN_ID, txnId)
            putExtra(CategorySelectionActivity.EXTRA_MERCHANT, merchant)
            putExtra(CategorySelectionActivity.EXTRA_TXN_TYPE, txnType)
            // compatibility for old screen consumers if reused
            putExtra(MerchantCategoryActivity.EXTRA_TXN_ID, txnId)
            putExtra(MerchantCategoryActivity.EXTRA_MERCHANT, merchant)
            putExtra(MerchantCategoryActivity.EXTRA_AMOUNT, amount)
            putExtra(MerchantCategoryActivity.EXTRA_TXN_TYPE, txnType)
        }
        val pi = PendingIntent.getActivity(
            app,
            txnId.hashCode() and 0x7FFF,
            intent,
            pendingFlags()
        )
        val notification = NotificationCompat.Builder(app, TransactionNotificationHelper.CHANNEL_TXN_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Set Category", pi)
            .build()
        try {
            val id = 9200 + (txnId.hashCode() and 0xFFF)
            NotificationManagerCompat.from(app).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted", e)
        }
    }

    private fun pendingFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f = f or PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }
}
