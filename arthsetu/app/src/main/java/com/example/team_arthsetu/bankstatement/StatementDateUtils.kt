package com.example.team_arthsetu.bankstatement

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Parse [date] strings from statements for optional range filtering.
 */
object StatementDateUtils {

    private val formats = listOf(
        SimpleDateFormat("dd/MM/yyyy", Locale.US),
        SimpleDateFormat("dd-MM-yyyy", Locale.US),
        SimpleDateFormat("dd/MM/yy", Locale.US),
        SimpleDateFormat("dd-MM-yy", Locale.US),
        SimpleDateFormat("dd-MMM-yy", Locale.US),
        SimpleDateFormat("dd-MMM-yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    )

    fun parseToMillis(date: String): Long? {
        val t = date.trim()
        if (t.isBlank()) return null
        for (f in formats) {
            f.isLenient = false
            try {
                return f.parse(t)?.time
            } catch (_: ParseException) { }
        }
        return null
    }

    fun parseDdMmYyyy(s: String): Calendar? {
        val t = s.trim()
        if (t.length != 10) return null
        val p = t.split('/')
        if (p.size != 3) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val y = p[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, if (y < 100) 2000 + y else y)
        cal.set(Calendar.MONTH, m - 1)
        cal.set(Calendar.DAY_OF_MONTH, d)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
}
