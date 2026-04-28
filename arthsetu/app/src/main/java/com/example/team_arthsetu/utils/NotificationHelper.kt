package com.example.team_arthsetu.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.team_arthsetu.R

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    private fun canPostLimitAlerts(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Limit alert not shown: grant POST_NOTIFICATIONS in app settings")
                return false
            }
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Log.w(TAG, "Limit alert not shown: notifications disabled for this app in system settings")
            return false
        }
        return true
    }

    const val CHANNEL_ID   = "expense_limit_alerts"
    const val CHANNEL_NAME = "Expense Limit Alerts"
    private var notifId    = 2000

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alert when your spending exceeds the set limit"
                enableVibration(true)
            }
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Shows a high-priority notification when a spending limit is exceeded.
     * @param period  "Daily" | "Weekly" | "Monthly" | "Quarterly"
     * @param spent   actual amount spent
     * @param limit   configured limit
     */
    fun showLimitExceeded(ctx: Context, period: String, spent: Double, limit: Double) {
        if (!canPostLimitAlerts(ctx)) return
        createChannel(ctx)

        val overspend = spent - limit
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ $period Expense Limit Exceeded!")
            .setContentText(
                "Spent ₹${fmt(spent)} · Limit ₹${fmt(limit)} · Over by ₹${fmt(overspend)}"
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your $period spending limit is ₹${fmt(limit)}.\n" +
                    "You have spent ₹${fmt(spent)} — exceeding by ₹${fmt(overspend)}.\n" +
                    "Review your expenses and adjust your spending."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(notifId++, notification)
        } catch (e: SecurityException) {
            // Permission revoked between check and notify; silently skip
        }
    }

    /**
     * Local backup alert shown from SMS worker when offline/network-lag occurs.
     * Fires for every NEW transaction after the limit is exceeded.
     */
    fun showLocalLimitExceededBackup(ctx: Context, limit: Double, spent: Double, lastAmount: Double) {
        if (!canPostLimitAlerts(ctx)) return

        createChannel(ctx)
        val message = "Limit: ₹${fmt(limit)}\nSpent: ₹${fmt(spent)}\nLast: ₹${fmt(lastAmount)}"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚠️ Limit Exceeded")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notifId++, notification)
        } catch (_: SecurityException) { }
    }

    /**
     * Manual display for FCM payloads (foreground + data+notification delivery).
     * Keeps channel + styling consistent with local backup alerts.
     */
    fun showFcmNotification(ctx: Context, title: String, body: String, notificationId: Int) {
        if (!canPostLimitAlerts(ctx)) return
        createChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId, notification)
        } catch (_: SecurityException) { }
    }

    /**
     * Per-category monthly limit (local backup or FCM); [period] label is usually "Monthly".
     */
    fun showCategoryLimitExceeded(
        ctx: Context,
        category: String,
        spent: Double,
        limit: Double,
        lastAmount: Double
    ) {
        if (!canPostLimitAlerts(ctx)) return
        createChannel(ctx)
        val over = spent - limit
        val title = "⚠ $category over monthly limit"
        val text = "Spent ₹${fmt(spent)} · Limit ₹${fmt(limit)} · Over ₹${fmt(over)} · Last txn ₹${fmt(lastAmount)}"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notifId++, notification)
        } catch (_: SecurityException) { }
    }

    private fun fmt(v: Double) = String.format("%.0f", v)
}
