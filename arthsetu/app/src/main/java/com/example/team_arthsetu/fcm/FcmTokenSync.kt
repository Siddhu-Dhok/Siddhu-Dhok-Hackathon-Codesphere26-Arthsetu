package com.example.team_arthsetu.fcm

import android.content.Context
import android.util.Log
import com.example.team_arthsetu.repository.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fetches the current FCM registration token and stores it under [FirestoreRepository.updateFcmToken].
 * Call after login and from [MainActivity] when session already exists.
 */
object FcmTokenSync {

    private const val TAG = "FcmTokenSync"

    fun requestTokenAndSyncToFirestore(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            flushPendingTokenIfSignedIn(app)
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "getToken failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            if (token.isNullOrBlank()) {
                Log.w(TAG, "getToken returned empty")
                return@addOnCompleteListener
            }
            Log.i(TAG, "FCM token obtained (len=${token.length})")
            Log.d(
                "FCM_DEBUG",
                "getToken success len=${token.length} preview=${token.take(16)}…"
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (FirebaseAuth.getInstance().currentUser == null) {
                        FcmTokenPendingStore.save(app, token)
                        Log.w(TAG, "FCM token cached: user not signed in yet")
                        return@launch
                    }
                    FirestoreRepository().updateFcmToken(token)
                    FcmTokenPendingStore.clear(app)
                    Log.i(TAG, "FCM token synced to Firestore")
                    Log.d("FCM_DEBUG", "Token written to users/{uid}.fcmToken in Firestore")
                } catch (e: Exception) {
                    Log.e(TAG, "FCM token Firestore write failed", e)
                }
            }
        }
    }

    /**
     * Writes a token that was saved in [FcmTokenPendingStore] during [ArthFirebaseMessagingService.onNewToken]
     * when no user was signed in.
     */
    suspend fun flushPendingTokenIfSignedIn(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val pending = FcmTokenPendingStore.peek(context) ?: return
        try {
            FirestoreRepository().updateFcmToken(pending)
            FcmTokenPendingStore.clear(context)
            Log.i(TAG, "Pending FCM token flushed to Firestore")
        } catch (e: Exception) {
            Log.e(TAG, "flush pending FCM token failed", e)
        }
    }
}
