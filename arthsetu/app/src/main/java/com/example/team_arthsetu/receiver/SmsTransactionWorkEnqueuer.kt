package com.example.team_arthsetu.receiver

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.team_arthsetu.utils.TransactionIdFactory
import java.util.concurrent.TimeUnit

/**
 * Enqueues [SmsTransactionWorker] — receiver stays lightweight.
 *
 * **Deduplication:** [enqueueUniqueWork] with [ExistingWorkPolicy.KEEP] and a fixed name
 * `sms_txn_{dedupeKey}`. If work with that name is already **enqueued or running**, the new
 * request is **not** scheduled (no duplicate workers for the same logical SMS).
 *
 * `dedupeKey` is a short hash of the **normalized** SMS body ([TransactionIdFactory.workDedupeKey]).
 */
object SmsTransactionWorkEnqueuer {

    private const val TAG = "SmsPipeline"

    /** Must match: `sms_txn_{dedupeKey}` — single unique-work chain per logical SMS. */
    private const val UNIQUE_WORK_PREFIX = "sms_txn_"

    fun enqueue(context: Context, body: String, smsDateMillis: Long) {
        val normalized = TransactionIdFactory.normalizeSmsBody(body)
        val dedupeKey = TransactionIdFactory.workDedupeKey(normalized)
        val uniqueName = UNIQUE_WORK_PREFIX + dedupeKey

        val data = Data.Builder()
            .putString(SmsTransactionWorker.KEY_BODY, body)
            .putLong(SmsTransactionWorker.KEY_SMS_DATE, smsDateMillis)
            .build()

        val work = OneTimeWorkRequestBuilder<SmsTransactionWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10_000, TimeUnit.MILLISECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val wm = WorkManager.getInstance(context.applicationContext)
        wm.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            work
        )
        Log.i(
            TAG,
            "enqueueUniqueWork policy=KEEP uniqueName=$uniqueName dedupeKey=$dedupeKey bodyLen=${body.length} " +
                "(if pending/running with same name, WorkManager keeps existing work)"
        )
    }
}
