package com.example.team_arthsetu.utils

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun Double.toRupees(): String {
    val fmt = NumberFormat.getNumberInstance(Locale("en", "IN"))
    fmt.minimumFractionDigits = 0
    fmt.maximumFractionDigits = 2
    return "₹${fmt.format(this)}"
}

fun Long.toDateString(pattern: String = "dd MMM yyyy, hh:mm a"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))

/**
 * Smart relative date:
 *  - Today           → "Today  10:24 AM"
 *  - Yesterday       → "Yesterday  08:05 PM"
 *  - Within 6 days   → "Mon  03:30 PM"
 *  - Older           → "25 Mar  10:24 AM"
 */
fun Long.toSmartDate(): String {
    val txnCal = Calendar.getInstance().apply { timeInMillis = this@toSmartDate }
    val nowCal  = Calendar.getInstance()

    val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val timeStr = timeFmt.format(Date(this))

    // Compare year + day-of-year
    val sameYear = txnCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
    val dayDiff  = if (sameYear)
        nowCal.get(Calendar.DAY_OF_YEAR) - txnCal.get(Calendar.DAY_OF_YEAR)
    else Int.MAX_VALUE

    return when {
        dayDiff == 0  -> "Today  $timeStr"
        dayDiff == 1  -> "Yesterday  $timeStr"
        dayDiff in 2..6 -> {
            val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(this))
            "$dayName  $timeStr"
        }
        else -> {
            val dateFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
            "${dateFmt.format(Date(this))}  $timeStr"
        }
    }
}
