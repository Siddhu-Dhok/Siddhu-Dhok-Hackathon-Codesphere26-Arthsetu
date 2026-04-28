package com.example.team_arthsetu.fcm

import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.team_arthsetu.R
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives FCM data/notification payloads from Cloud Functions (e.g. expense limit exceeded).
 * Local limit checks are no longer shown from the client; the backend sends pushes when needed.
 */
class ArthFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("ArthFCM", "Message received")
        Log.d(
            TAG_FCM_DEBUG,
            "messageId=${message.messageId} from=${message.from} " +
                "notification=${message.notification != null} dataOnly=${message.notification == null} " +
                "dataKeys=${message.data.keys}"
        )
        val data = message.data
        val msgType = data["type"]?.trim().orEmpty()
        if (msgType.equals(MerchantCategoryFcmTrigger.FCM_TYPE, ignoreCase = true) ||
            msgType.equals("MERCHANT_CATEGORY", ignoreCase = true) ||
            msgType.equals(MerchantCategoryFcmTrigger.FCM_TYPE_CATEGORY_REQUIRED, ignoreCase = true)
        ) {
            MerchantCategoryFcmTrigger.handleIncomingDataMessage(this, data)
            return
        }

        val resolved = resolveNotificationContent(message)
        if (resolved == null) {
            Log.w(TAG, "FCM ignored: no title/body after notification+data fallbacks keys=${data.keys}")
            return
        }
        var (title, body) = resolved
        if (msgType.equals("LIMIT_ALERT", ignoreCase = true)) {
            val periodLabel = data["period"]?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            } ?: "Daily"
            val limit = data["limit"]?.takeIf { it.isNotBlank() } ?: data["daily_limit"].orEmpty()
            val spent = data["total"]?.takeIf { it.isNotBlank() } ?: data["daily_total"].orEmpty()
            val last = data["amount"].orEmpty()
            title = "⚠️ Expense Limit Exceeded"
            body = "You have exceeded your $periodLabel limit.\nLimit: ₹$limit\nSpent: ₹$spent\nLast: ₹$last"
        }
        if (msgType.equals("LIMIT_WARNING_80", ignoreCase = true)) {
            val periodLabel = data["period"]?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            } ?: "Daily"
            val limit = data["limit"]?.takeIf { it.isNotBlank() }.orEmpty()
            val spent = data["total"]?.takeIf { it.isNotBlank() }.orEmpty()
            val pct = data["threshold_percent"]?.takeIf { it.isNotBlank() } ?: "80"
            title = "⚠️ $pct% of $periodLabel limit used"
            body = "You're at or above $pct% of your $periodLabel expense limit.\nLimit: ₹$limit\nSpent: ₹$spent"
        }
        if (msgType.equals("CATEGORY_LIMIT_ALERT", ignoreCase = true)) {
            val cat = data["category"].orEmpty().ifBlank { "Category" }
            val limit = data["limit"].orEmpty()
            val spent = data["total"].orEmpty()
            val last = data["amount"].orEmpty()
            title = "⚠️ $cat over monthly limit"
            body = "Limit: ₹$limit\nSpent: ₹$spent\nLast txn: ₹$last"
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.e(TAG, "FCM notify blocked: notifications disabled for app in system settings")
            return
        }

        val id = message.data["notificationId"]?.toIntOrNull()
            ?: message.data["notification_id"]?.toIntOrNull()
            ?: (9100 + (message.messageId?.hashCode() ?: 0) and 0xFFFF)
        NotificationHelper.showFcmNotification(this, title, body, id)
        Log.d("ArthFCM", "Notification posted via NotificationHelper id=$id")
        Log.d(TAG_FCM_DEBUG, "channel=${NotificationHelper.CHANNEL_ID}")
    }

    /**
     * Resolves title/body from [RemoteMessage.notification], then common [RemoteMessage.data] keys.
     * Avoids silent drops when the backend sends data-only payloads without `title` (e.g. `subject` only).
     */
    private fun resolveNotificationContent(message: RemoteMessage): Pair<String, String>? {
        val n = message.notification
        val d = message.data

        var title = n?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstNonBlank(d, "title", "subject", "gcm.notification.title", "alert_title")
        var body = n?.body?.trim().orEmpty()
        if (body.isEmpty()) {
            body = firstNonBlank(d, "body", "message", "alert", "gcm.notification.body", "body_text").orEmpty()
        }
        if (body.isEmpty() && d.size == 1) {
            d.values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { body = it }
        }

        if (title == null && body.isNotEmpty()) {
            title = getString(R.string.app_name)
        }
        if (title == null && body.isEmpty() && d.isEmpty() && n == null) {
            return null
        }
        if (title == null && body.isEmpty()) {
            return null
        }
        return Pair(title ?: getString(R.string.app_name), body)
    }

    private fun firstNonBlank(data: Map<String, String>, vararg keys: String): String? {
        for (k in keys) {
            data[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            data.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }
                ?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    override fun onNewToken(token: String) {
        Log.d(TAG_FCM_DEBUG, "onNewToken len=${token.length} preview=${token.take(16)}…")
        Log.i(TAG, "onNewToken len=${token.length}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (FirebaseAuth.getInstance().currentUser == null) {
                    FcmTokenPendingStore.save(applicationContext, token)
                    Log.w(TAG, "onNewToken: no signed-in user; token cached for post-login sync")
                    return@launch
                }
                FirestoreRepository().updateFcmToken(token)
                FcmTokenPendingStore.clear(applicationContext)
                Log.i(TAG, "onNewToken persisted to Firestore")
            } catch (e: Exception) {
                Log.e(TAG, "updateFcmToken failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ArthFCM"
        /** Filter Logcat by this tag for limit-alert / FCM delivery debugging */
        private const val TAG_FCM_DEBUG = "FCM_DEBUG"
    }
}
