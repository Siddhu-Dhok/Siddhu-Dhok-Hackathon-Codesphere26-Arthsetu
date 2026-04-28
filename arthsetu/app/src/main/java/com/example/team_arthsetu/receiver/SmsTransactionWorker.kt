package com.example.team_arthsetu.receiver

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.team_arthsetu.fcm.FcmTokenSync
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.CategoryResolver
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.LocalLimitAlertHelper
import com.example.team_arthsetu.utils.NetworkUnavailableException
import com.example.team_arthsetu.utils.SmsPipelineNotifications
import com.example.team_arthsetu.utils.TransactionParser
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import java.net.UnknownHostException

/**
 * Parses inbound SMS and writes to Firestore. Retries on transient inbox/Firestore failures.
 */
class SmsTransactionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork() start workId=$id runAttempt=$runAttemptCount")

        val body = inputData.getString(KEY_BODY) ?: run {
            Log.e(TAG, "failure: missing input KEY_BODY")
            return Result.failure(workDataOf("reason" to "missing_body"))
        }
        val smsDate = inputData.getLong(KEY_SMS_DATE, 0L)
        if (smsDate == 0L) {
            Log.e(TAG, "failure: missing or zero KEY_SMS_DATE")
            return Result.failure(workDataOf("reason" to "missing_sms_date"))
        }

        Log.i(TAG, "worker processing attempt=$runAttemptCount bodyLen=${body.length} smsDate=$smsDate")

        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.w(
                TAG,
                "BLOCKED: FirebaseAuth has no user on attempt=$runAttemptCount. " +
                    "Likely cold start/session restore in progress; retrying."
            )
            return Result.retry()
        }

        if (!TransactionParser.isBankTransaction(body)) {
            Log.i(TAG, "skip: not classified as bank transaction SMS")
            return Result.success()
        }

        val txn = TransactionParser.parseToTransaction(body, smsDate)
        if (txn == null) {
            Log.e(TAG, "parse failed permanently — cannot build Transaction body=\"$body\"")
            return Result.failure(workDataOf("reason" to "parse_failed"))
        }

        val classificationHint = buildString {
            append(txn.merchant)
            if (txn.note.isNotBlank()) {
                append(' ')
                append(txn.note)
            }
        }
        val category = CategoryResolver.resolve(
            applicationContext,
            txn.merchant,
            classificationHint,
            txn.type
        )
        val toSave = txn.copy(category = category)

        // Offline-first backup: daily/weekly/monthly/quarterly + per-category (no Firestore required).
        if (toSave.type.equals(Constants.DEBIT, ignoreCase = true)) {
            LocalLimitAlertHelper.checkAfterDebit(
                applicationContext,
                toSave.id,
                toSave.amount,
                toSave.timestamp,
                toSave.category
            )
        }

        return try {
            Log.i(TAG, "Processing transaction from source=SMS")
            Log.i(TAG, "transactionId=${toSave.id}")
            val repo = FirestoreRepository()
            FcmTokenSync.requestTokenAndSyncToFirestore(applicationContext)
            repo.pushLimitSettingsAndPendingFcmBeforeTxnWrite(applicationContext)
            val inserted = repo.addTransactionWithIdIfAbsent(toSave)
            if (!inserted) {
                Log.i(TAG, "duplicate SMS skipped (transactionId already in Firestore) transactionId=${toSave.id}")
                return Result.success()
            }
            Log.i(TAG, "Firestore write ok inserted transactionId=${toSave.id}")
            SmsPipelineNotifications.afterDebitSavedFromSms(applicationContext, toSave)
            Result.success()
        } catch (e: NetworkUnavailableException) {
            Log.w(TAG, "Firestore write skipped or blocked: no network — will retry with backoff", e)
            Result.retry()
        } catch (e: FirebaseFirestoreException) {
            Log.e(TAG, "Firestore error code=${e.code}", e)
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> {
                    Log.e(
                        TAG,
                        "Firestore rules/auth blocked write — fix Firestore rules & auth. Not retrying."
                    )
                    Result.failure(
                        workDataOf(
                            "reason" to "firestore_permission",
                            "code" to e.code.name
                        )
                    )
                }
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> {
                    Log.w(TAG, "transient Firestore error — will retry with backoff")
                    Result.retry()
                }
                else -> Result.retry()
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Unable to resolve host — will retry with backoff", e)
            Result.retry()
        } catch (e: Exception) {
            if (e.cause is UnknownHostException) {
                Log.w(TAG, "Network resolution failure — will retry with backoff", e)
                return Result.retry()
            }
            Log.e(TAG, "Firestore write failed", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_BODY = "body"
        const val KEY_SMS_DATE = "sms_date"
        private const val TAG = "SmsPipeline"
    }
}
