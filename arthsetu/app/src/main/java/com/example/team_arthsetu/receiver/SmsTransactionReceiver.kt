package com.example.team_arthsetu.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.team_arthsetu.fcm.FcmTokenSync
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.CategoryResolver
import com.example.team_arthsetu.utils.SmsPipelineNotifications
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.LocalLimitAlertHelper
import com.example.team_arthsetu.utils.TransactionParser
import com.example.team_arthsetu.utils.TransactionIdFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * **Bank SMS pipeline (app open or in background):**
 *
 * 1. `SMS_RECEIVED` broadcast → concat multipart SMS.
 * 2. [TransactionParser] filters bank-like messages and parses amount, merchant, type, etc.
 * 3. [CategoryResolver] assigns category (local learned map → Firestore → heuristic rules).
 * 4. [FirestoreRepository.addTransactionWithIdIfAbsent] writes `users/{uid}/transactions/{id}` (expenses list).
 * 5. [SmsPipelineNotifications] posts a notification: “choose category” if needed, else “expense saved”.
 *
 * If the user is signed out or the write fails, [SmsTransactionWorkEnqueuer] retries via [SmsTransactionWorker].
 */
class SmsTransactionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val recvGranted = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
                val readGranted = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "SMS_RECEIVED broadcast hit permissions receive=$recvGranted read=$readGranted")

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) {
                    Log.d(TAG, "SMS_RECEIVED: no message parts, skip")
                    return@launch
                }

                val from = messages.firstOrNull()?.originatingAddress.orEmpty()
                val fullBody = messages.joinToString(separator = "") { m -> m.messageBody ?: "" }
                if (fullBody.isBlank()) {
                    Log.d(TAG, "SMS_RECEIVED: blank body after concat, skip")
                    return@launch
                }

                val smsDate = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                val dedupeKey = TransactionIdFactory.workDedupeKey(TransactionIdFactory.normalizeSmsBody(fullBody))
                Log.i(
                    TAG,
                    "SMS_RECEIVED from=$from len=${fullBody.length} date=$smsDate transactionDedupeKey=$dedupeKey body=\"$fullBody\""
                )

                if (!TransactionParser.isBankTransaction(fullBody)) {
                    Log.i(TAG, "SMS_RECEIVED skip non-transaction SMS")
                    return@launch
                }

                val parsed = TransactionParser.parseToTransaction(fullBody, smsDate)
                if (parsed == null) {
                    Log.w(TAG, "SMS_RECEIVED parse failed; enqueueing worker fallback dedupeKey=$dedupeKey")
                    SmsTransactionWorkEnqueuer.enqueue(context.applicationContext, fullBody, smsDate)
                    return@launch
                }

                val hint = buildString {
                    append(parsed.merchant)
                    if (parsed.note.isNotBlank()) append(" ${parsed.note}")
                }
                val resolvedCategory = CategoryResolver.resolve(
                    context.applicationContext,
                    parsed.merchant,
                    hint,
                    parsed.type
                )
                val toSave = parsed.copy(category = resolvedCategory)

                if (toSave.type.equals(Constants.DEBIT, ignoreCase = true)) {
                    LocalLimitAlertHelper.checkAfterDebit(
                        context.applicationContext,
                        toSave.id,
                        toSave.amount,
                        toSave.timestamp,
                        toSave.category
                    )
                }

                try {
                    FcmTokenSync.requestTokenAndSyncToFirestore(context.applicationContext)
                    FirestoreRepository().pushLimitSettingsAndPendingFcmBeforeTxnWrite(
                        context.applicationContext
                    )
                    val inserted = FirestoreRepository().addTransactionWithIdIfAbsent(toSave)
                    Log.i(TAG, "SMS_RECEIVED immediate write inserted=$inserted transactionId=${toSave.id}")
                    if (inserted) {
                        SmsPipelineNotifications.afterDebitSavedFromSms(context.applicationContext, toSave)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SMS_RECEIVED immediate write failed; enqueueing worker fallback", e)
                    SmsTransactionWorkEnqueuer.enqueue(context.applicationContext, fullBody, smsDate)
                    Log.i(TAG, "SMS_RECEIVED enqueued worker dedupeKey=$dedupeKey")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsPipeline"
    }
}
