package com.example.team_arthsetu.fcm

import android.content.Context

/**
 * Persists the FCM registration token when [com.google.firebase.auth.FirebaseAuth] has no user yet.
 * [FcmTokenSync] flushes to Firestore after login.
 */
object FcmTokenPendingStore {

    private const val PREF = "fcm_token_pending_store"
    private const val KEY = "pending_token"

    fun save(context: Context, token: String) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, token)
            .apply()
    }

    fun peek(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null)

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
