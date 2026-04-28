package com.example.team_arthsetu.utils

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.example.team_arthsetu.R
import com.google.android.material.snackbar.Snackbar

/**
 * Classic light **Toast** (not a Snackbar): app icon + message, rounded pill-style background.
 * Call sites keep using [AppSnackbar] / [showLong] for a consistent, non-intrusive message.
 */
object AppSnackbar {

    fun show(anchor: View, message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        showInternal(anchor.context.applicationContext, message, snackbarDurationToToast(duration))
    }

    fun showLong(anchor: View, message: String) {
        showInternal(anchor.context.applicationContext, message, Toast.LENGTH_LONG)
    }

    /** When you only have a [Context] (no anchor view). */
    fun show(context: Context, message: String, longDuration: Boolean = false) {
        val len = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        showInternal(context.applicationContext, message, len)
    }

    private fun snackbarDurationToToast(snackDuration: Int): Int = when (snackDuration) {
        Snackbar.LENGTH_LONG -> Toast.LENGTH_LONG
        Snackbar.LENGTH_INDEFINITE -> Toast.LENGTH_LONG
        else -> Toast.LENGTH_SHORT
    }

    @Suppress("DEPRECATION")
    private fun showInternal(appCtx: Context, message: String, toastLength: Int) {
        val inflater = LayoutInflater.from(appCtx)
        val root = inflater.inflate(R.layout.toast_classic, null)
        root.findViewById<TextView>(R.id.toast_message).apply {
            text = message
        }

        val yOffset = (88 * appCtx.resources.displayMetrics.density).toInt()
        val toast = Toast(appCtx)
        toast.duration = toastLength
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, yOffset)
        toast.view = root
        toast.show()
    }
}
