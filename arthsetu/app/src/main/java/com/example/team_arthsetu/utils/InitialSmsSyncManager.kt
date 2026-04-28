package com.example.team_arthsetu.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.team_arthsetu.receiver.SmsTransactionWorkEnqueuer
import com.example.team_arthsetu.repository.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-time backfill of older inbox SMS into Firestore after SMS permission is granted.
 * Keeps existing SMS receiver/worker/category architecture unchanged.
 */
object InitialSmsSyncManager {

    private const val TAG = "InitialSmsSync"
    private const val PREF = "sms_sync_prefs"
    private const val KEY_DONE = "initial_sms_sync_done"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Non-blocking trigger. Safe to call many times; actual sync runs once via SharedPreferences flag.
     */
    fun performInitialSmsSync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { performInitialSmsSyncInternal(app) }
                .onFailure { Log.e(TAG, "initial sync crashed", it) }
        }
    }

    private suspend fun performInitialSmsSyncInternal(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) {
            Log.d(TAG, "skip: initial sync already completed")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "skip: READ_SMS permission missing")
            return
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.w(TAG, "skip: no signed-in user for Firestore writes")
            return
        }

        val repo = FirestoreRepository()
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "body", "date")
        var processed = 0
        var parsed = 0
        var inserted = 0
        var enqueuedRetry = 0

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "date DESC"
        )?.use { cursor ->
            val bodyIx = cursor.getColumnIndex("body")
            val dateIx = cursor.getColumnIndex("date")
            while (cursor.moveToNext()) {
                processed++
                if (bodyIx == -1 || dateIx == -1) continue
                val body = cursor.getString(bodyIx).orEmpty()
                val smsDate = cursor.getLong(dateIx)
                if (body.isBlank()) continue
                if (!TransactionParser.isBankTransaction(body)) continue

                val txn = TransactionParser.parseToTransaction(body, smsDate) ?: continue
                parsed++

                val hint = buildString {
                    append(txn.merchant)
                    if (txn.note.isNotBlank()) {
                        append(' ')
                        append(txn.note)
                    }
                }
                val category = CategoryResolver.resolve(context, txn.merchant, hint, txn.type)
                val toSave = txn.copy(category = category)

                try {
                    val didInsert = repo.addTransactionWithIdIfAbsent(toSave)
                    if (didInsert) inserted++
                } catch (e: Exception) {
                    // Use existing worker retry pipeline when Firestore write fails.
                    SmsTransactionWorkEnqueuer.enqueue(context, body, smsDate)
                    enqueuedRetry++
                    Log.w(TAG, "write failed, enqueued retry for smsDate=$smsDate", e)
                }

                if (processed % 100 == 0) {
                    Log.i(TAG, "progress processed=$processed parsed=$parsed inserted=$inserted retry=$enqueuedRetry")
                }
            }
        }

        prefs.edit().putBoolean(KEY_DONE, true).apply()
        Log.i(
            TAG,
            "initial sync complete processed=$processed parsed=$parsed inserted=$inserted retry=$enqueuedRetry"
        )
    }
}
