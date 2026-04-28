package com.example.team_arthsetu

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.team_arthsetu.utils.NotificationHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Ensures the FCM default notification channel exists before any message is shown
 * (including system-handled notification payloads when the app is in the background).
 */
class ArthApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        FirebaseApp.initializeApp(this)

        // Android: local cache / offline persistence is on by default; no deprecated settings API needed.
        FirebaseFirestore.getInstance()
        Log.i(TAG, "FirebaseFirestore initialized (offline persistence enabled by default on Android)")

        logGooglePlayServicesStatus()

        NotificationHelper.createChannel(this)
    }

    private fun logGooglePlayServicesStatus() {
        val availability = GoogleApiAvailability.getInstance()
        val code = availability.isGooglePlayServicesAvailable(this)
        if (code == ConnectionResult.SUCCESS) {
            Log.i(TAG, "Google Play services available")
        } else {
            Log.e(
                TAG,
                "Google Play services not available (code=$code). " +
                    "FCM/Firebase may fail until Play services is updated or repaired."
            )
        }
    }

    companion object {
        private const val TAG = "ArthApplication"

        lateinit var appContext: Context
            private set
    }
}
