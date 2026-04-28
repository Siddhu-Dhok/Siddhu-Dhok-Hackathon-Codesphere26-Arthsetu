package com.example.team_arthsetu.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.MainActivity
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.ui.MerchantCategoryActivity
import com.example.team_arthsetu.receiver.TransactionCategoryReceiver
import com.example.team_arthsetu.R
import java.util.Locale

/**
 * System notifications for new bank SMS transactions: shows debit/credit, amount,
 * merchant, and actions to:
 *   1. Quick-pick a category (action buttons)
 *   2. Type a custom category via RemoteInput inline reply
 *
 * Uses app logo, detailed notification layout, and the full category list from Constants.
 */
object TransactionNotificationHelper {

    const val CHANNEL_TXN_ID = "arthsetu_transactions"
    private const val CHANNEL_NAME = "Transaction Categories"

    const val REMOTE_INPUT_KEY = "category_typed"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_TXN_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New bank SMS — choose a category without opening the app"
                enableVibration(true)
            }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notificationIdFor(txnId: String): Int =
        (txnId.hashCode() and 0x7FFFFFFF)

    /** Distinct from [notificationIdFor] so “expense saved” does not replace the categorize notification. */
    private fun notificationIdExpenseLogged(txnId: String): Int =
        (notificationIdFor(txnId) + 17_011_013) and 0x7FFFFFFF

    /**
     * Compact confirmation after an SMS debit was parsed, categorized, and stored in Firestore.
     */
    fun showExpenseLoggedFromSms(context: Context, txn: Transaction) {
        if (!txn.type.equals(Constants.DEBIT, ignoreCase = true)) return
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val app = context.applicationContext
        val openApp = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            app,
            notificationIdExpenseLogged(txn.id),
            openApp,
            pendingFlags()
        )
        val cat = txn.category.trim().ifBlank { "Uncategorized" }
        val title = "${CategoryClassifier.iconFor(cat)} Expense saved"
        val text = "₹${fmt(txn.amount)} · ${txn.merchant} · $cat"
        val builder = NotificationCompat.Builder(app, CHANNEL_TXN_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
        try {
            NotificationManagerCompat.from(app).notify(
                notificationIdExpenseLogged(txn.id),
                builder.build()
            )
        } catch (_: SecurityException) {
        }
    }

    /**
     * Shows a rich notification with:
     * - App logo as icon
     * - Transaction type (Debit/Credit), amount, merchant
     * - Up to 2 quick-pick category buttons (most common)
     * - A "Type category" RemoteInput to type any custom category name inline
     * - BigText style with all available categories listed for reference
     *
     * Android limits notifications to max 3 action buttons, so we use:
     *   [Most-likely category] [2nd-likely] [✏ Type category]
     */
    fun showCategorizeNotification(context: Context, txn: Transaction) {
        if (!txn.type.equals(Constants.DEBIT, ignoreCase = true)) return
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val app = context.applicationContext

        val typeLabel = if (txn.type == "credit") "💰 Credit" else "💸 Debit"
        val title = "$typeLabel · ₹${fmt(txn.amount)}"
        val shortText = "${txn.merchant} — choose a category"

        // All categories for the BigText reference
        val allCats = Constants.CATEGORIES.joinToString(" • ")

        val openCategorize = PendingIntent.getActivity(
            app,
            notificationIdFor(txn.id),
            Intent(app, MerchantCategoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MerchantCategoryActivity.EXTRA_TXN_ID, txn.id)
                putExtra(MerchantCategoryActivity.EXTRA_MERCHANT, txn.merchant)
                putExtra(MerchantCategoryActivity.EXTRA_AMOUNT, txn.amount)
                putExtra(MerchantCategoryActivity.EXTRA_TXN_TYPE, txn.type)
            },
            pendingFlags()
        )

        val builder = NotificationCompat.Builder(app, CHANNEL_TXN_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        appendLine("$typeLabel  ₹${fmt(txn.amount)}")
                        appendLine("Merchant: ${txn.merchant}")
                        appendLine()
                        appendLine("Choose a category below, or type a custom one:")
                        appendLine()
                        appendLine("Available: $allCats")
                        appendLine()
                        append("Tap \"Type category\" to enter any name. ")
                        append("Future transactions from this merchant will auto-use the same category.")
                    }
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openCategorize)

        // ── Quick-pick action buttons (2 most common categories) ──────────
        // Android notification max = 3 actions, so 2 quick-picks + 1 RemoteInput
        val quickPicks = listOf("Food", "Shopping")
        for (cat in quickPicks) {
            builder.addAction(
                0,
                CategoryClassifier.iconFor(cat) + " " + cat,
                categoryBroadcastIntent(app, txn, cat)
            )
        }

        // ── "Type category" RemoteInput action ────────────────────────────
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("Type category name (e.g. ${Constants.CATEGORIES.random()})")
            .build()

        val customIntent = Intent(app, TransactionCategoryReceiver::class.java).apply {
            putExtra(TransactionCategoryReceiver.EXTRA_TXN_ID, txn.id)
            putExtra(TransactionCategoryReceiver.EXTRA_MERCHANT, txn.merchant)
            putExtra(TransactionCategoryReceiver.EXTRA_TXN_TYPE, txn.type)
        }

        val customPi = PendingIntent.getBroadcast(
            app,
            notificationIdFor(txn.id) + 901,
            customIntent,
            pendingFlagsMutable()
        )

        val typeAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "✏ Type category",
            customPi
        ).addRemoteInput(remoteInput).build()

        builder.addAction(typeAction)

        try {
            NotificationManagerCompat.from(app).notify(notificationIdFor(txn.id), builder.build())
        } catch (_: SecurityException) {}
    }

    private fun categoryBroadcastIntent(context: Context, txn: Transaction, category: String): PendingIntent {
        val intent = Intent(context, TransactionCategoryReceiver::class.java).apply {
            putExtra(TransactionCategoryReceiver.EXTRA_TXN_ID, txn.id)
            putExtra(TransactionCategoryReceiver.EXTRA_MERCHANT, txn.merchant)
            putExtra(TransactionCategoryReceiver.EXTRA_TXN_TYPE, txn.type)
            putExtra(TransactionCategoryReceiver.EXTRA_CATEGORY, category)
        }
        val req = notificationIdFor(txn.id) + category.hashCode()
        return PendingIntent.getBroadcast(context, req, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f = f or PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }

    /** RemoteInput delivery requires a mutable PendingIntent on API 31+. */
    private fun pendingFlagsMutable(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            f = f or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f = f or PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.0f", v)
}
