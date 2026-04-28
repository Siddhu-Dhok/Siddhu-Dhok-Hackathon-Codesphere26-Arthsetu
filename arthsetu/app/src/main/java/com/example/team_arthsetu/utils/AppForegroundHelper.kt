package com.example.team_arthsetu.utils

import android.app.ActivityManager
import android.content.Context

/** Returns true if any activity from this app is in the foreground. */
object AppForegroundHelper {

    fun isAppInForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val pkg = context.packageName
        return am.runningAppProcesses?.any {
            it.processName == pkg &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }
}
