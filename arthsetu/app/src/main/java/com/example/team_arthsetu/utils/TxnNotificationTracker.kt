package com.example.team_arthsetu.utils

import android.content.Context

/**
 * Avoids posting duplicate "categorise this transaction" notifications
 * for the same [Transaction.id] (e.g. SMS receiver + Expense sync).
 */
object TxnNotificationTracker {

    private const val PREF = "txn_notif_tracker"
    private const val KEY_SHOWN = "shown_ids"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun shouldShowNotification(context: Context, txnId: String): Boolean {
        val set = prefs(context).getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()
        return txnId !in set
    }

    fun markNotificationShown(context: Context, txnId: String) {
        val cur = prefs(context).getStringSet(KEY_SHOWN, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        cur.add(txnId)
        prefs(context).edit().putStringSet(KEY_SHOWN, cur).apply()
    }
}
