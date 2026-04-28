package com.example.team_arthsetu.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.team_arthsetu.model.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads bank SMS from the device inbox, parses transaction details, and
 * de-duplicates using reference numbers + (amount, type, date) composite keys.
 *
 * Supports all major Indian bank SMS formats:
 *   SBI, HDFC, ICICI, Axis, Kotak, BOB, PNB, Union Bank, IndusInd,
 *   Yes Bank, IDFC, Federal, Canara, IOB, Indian Bank, etc.
 */
object SmsParser {

    private const val TAG = "SmsParser"

    // ── Amount patterns ─────────────────────────────────────────────────────
    // Covers: Rs.500, Rs:500.00, Rs 1,500, INR 2500.50, ₹500
    private val amountRegex = Regex(
        """(?:INR|Rs[.:\s]?|₹)\s*([\d,]+(?:\.\d{1,2})?)|(?:debited|credited)\s+by\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
x
    // ── Debit / Credit keyword lists ────────────────────────────────────────
    private val debitKeywords = listOf(
        "debited", "debit", "spent", "purchase", "paid",
        "withdrawn", "payment", "sent", "dr", "deducted"
    )
    private val creditKeywords = listOf(
        "credited", "credit", "received", "deposit",
        "refund", "cr", "added", "reversed"
    )

    // ── Reference number extraction ─────────────────────────────────────────
    // Matches: ref no 120590070555, Ref No. 12345, ref:12345, txn#12345,
    //          UPI Ref 123456789012, IMPS Ref 412345678901
    private val refRegex = Regex(
        """(?:ref\s*(?:no\.?)?|txn\s*#?|upi\s*ref|imps\s*ref|neft\s*ref)[:\s]*(\d{6,20})""",
        RegexOption.IGNORE_CASE
    )

    // ── Merchant extraction patterns ────────────────────────────────────────
    // Ordered from most specific to most generic
    private val merchantPatterns = listOf(
        // "Info: MERCHANT NAME" or "Merchant: NAME"
        Regex("""(?:Info|Merchant)[:\s]+([A-Z0-9 &'.@/\-]{3,30})(?:\.|;|\n|Avl|Bal|Ref|$)""", RegexOption.IGNORE_CASE),
        // "VPA merchant@bank" (UPI VPA)
        Regex("""VPA\s+([A-Za-z0-9._]+@[A-Za-z]+)""", RegexOption.IGNORE_CASE),
        // "to MERCHANT on" or "at MERCHANT" or "trf to MERCHANT" or "for MERCHANT"
        Regex("""(?:at|to|trf\s+to|for|from)\s+([A-Z0-9 &'.@/\-]{3,40})(?:\s+on|\s+Ref(?:no)?|\.|;|\n|$)""", RegexOption.IGNORE_CASE),
        // "frm MERCHANT" (common SBI style: "Txn of Rs5.0 frm XYZ A/c...")
        Regex("""\bfrm\s+([A-Z0-9 &'.@/\-]{3,30})(?:\s+A/c|\s+on|\s+Ref|\.|;|\n|$)""", RegexOption.IGNORE_CASE),
        // "UPI/to/MERCHANT" or "UPI-MERCHANT" or "UPI MERCHANT"
        Regex("""UPI[/\s\-]+(?:to|from)?[/\s]*([A-Za-z0-9 @.\-]{3,25})(?:\.|;|\n|$)""", RegexOption.IGNORE_CASE),
        // "by Mob Bk" or "by NEFT" or "by IMPS" — extract transfer method as fallback
        Regex("""by\s+([A-Za-z ]{3,20})\s+ref""", RegexOption.IGNORE_CASE)
    )

    // ── Date patterns ───────────────────────────────────────────────────────
    // Covers: 26-03-2026, 26/03/2026, 26.03.2026, 26-Mar-2026, 26Mar2026,
    //         26-03-26, 26/03/26, 2026-03-26 (ISO)
    private data class DatePattern(val format: String, val regex: Regex)
    private val datePatterns = listOf(
        DatePattern("yyyy-MM-dd", Regex("""\b(\d{4}-\d{2}-\d{2})\b""")),           // ISO 2026-03-26
        DatePattern("dd-MMM-yyyy", Regex("""\b(\d{2}-[A-Za-z]{3}-\d{4})\b""")),     // 26-Mar-2026
        DatePattern("dd-MMM-yy",   Regex("""\b(\d{2}-[A-Za-z]{3}-\d{2})\b""")),     // 26-Mar-26
        DatePattern("ddMMMyyyy",   Regex("""\b(\d{2}[A-Za-z]{3}\d{4})\b""")),       // 26Mar2026
        DatePattern("ddMMMyy",     Regex("""\b(\d{2}[A-Za-z]{3}\d{2})\b""")),       // 26Mar26
        DatePattern("dd/MM/yyyy",  Regex("""\b(\d{2}/\d{2}/\d{4})\b""")),           // 26/03/2026
        DatePattern("dd-MM-yyyy",  Regex("""\b(\d{2}-\d{2}-\d{4})\b""")),           // 26-03-2026
        DatePattern("dd.MM.yyyy",  Regex("""\b(\d{2}\.\d{2}\.\d{4})\b""")),         // 26.03.2026
        DatePattern("dd/MM/yy",    Regex("""\b(\d{2}/\d{2}/\d{2})\b""")),           // 26/03/26
        DatePattern("dd-MM-yy",    Regex("""\b(\d{2}-\d{2}-\d{2})\b"""))            // 26-03-26
    )

    // Time pattern: "10:24:00", "10:24", "10:24 AM"
    private val timeRegex = Regex(
        """\b(\d{1,2}:\d{2}(?::\d{2})?)\s*(AM|PM)?\b""",
        RegexOption.IGNORE_CASE
    )

    // ── Balance extraction (to help with credit/debit detection) ─────────
    private val balanceRegex = Regex(
        """(?:Avl\s*Bal|Available\s*Balance|Bal|Balance)[:\s]*(?:INR|Rs[.:]?|₹)?\s*([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * True if the SMS body looks like a bank transaction (same rules as inbox parsing).
     */
    fun isBankTransactionSms(body: String): Boolean = looksLikeBankSms(body)

    /**
     * Parse a single SMS body into a [Transaction]. Used by [SmsTransactionReceiver].
     * @param smsDbId Row `_id` from `content://sms/inbox` (must match [readBankSms] IDs).
     */
    fun parseIncomingSms(body: String, smsDbId: Long, smsDate: Long): Transaction? {
        if (!looksLikeBankSms(body)) return null
        return parseTransaction(body, smsDbId, smsDate)
    }

    /**
     * Looks up the inbox `_id` for a message body (after SMS is stored).
     * Call with a short delay after [SMS_RECEIVED] so the provider has inserted the row.
     */
    fun findSmsInboxId(context: Context, body: String): Long? {
        if (body.isBlank()) {
            Log.w(TAG, "findSmsInboxId: blank body")
            return null
        }
        try {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "body = ?",
                arrayOf(body),
                "date DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    Log.i(TAG, "findSmsInboxId: exact body match _id=$id")
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findSmsInboxId: exact query failed (READ_SMS granted?)", e)
        }

        // Multipart / provider normalization: inbox text may differ from broadcast concat — match normalized body.
        val wantNorm = TransactionIdFactory.normalizeSmsBody(body)
        try {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "body"),
                null,
                null,
                "date DESC"
            )?.use { c ->
                var scanned = 0
                while (c.moveToNext() && scanned < 120) {
                    scanned++
                    val id = c.getLong(0)
                    val stored = c.getString(1) ?: continue
                    if (TransactionIdFactory.normalizeSmsBody(stored) == wantNorm) {
                        Log.i(TAG, "findSmsInboxId: normalized match _id=$id (scanned=$scanned)")
                        return id
                    }
                }
                Log.w(TAG, "findSmsInboxId: no row matched (normalized), scanned=$scanned")
            }
        } catch (e: Exception) {
            Log.e(TAG, "findSmsInboxId: fallback scan failed", e)
        }
        return null
    }

    /** Each pair is ([Transaction], raw SMS body) so callers can build the same Firestore id as [TransactionParser]. */
    fun readBankSms(context: Context): List<Pair<Transaction, String>> {
        val result      = mutableListOf<Pair<Transaction, String>>()
        val seenSmsIds  = mutableSetOf<Long>()       // deduplicate by SMS _id (database PK)
        val seenRefNos  = mutableSetOf<String>()      // deduplicate by bank reference number

        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null, null,
                "date DESC"
            ) ?: return result

            var rowCount = 0
            cursor.use { c ->
                while (c.moveToNext() && rowCount < 500) {
                    rowCount++
                    val smsDbId = c.getLong(0)
                    val body    = c.getString(2) ?: ""
                    val smsDate = c.getLong(3)

                    if (smsDbId in seenSmsIds) continue
                    seenSmsIds.add(smsDbId)

                    if (!looksLikeBankSms(body)) continue

                    val txn = parseTransaction(body, smsDbId, smsDate) ?: continue

                    // Skip if we already have a transaction with the same ref number
                    val refNo = extractRefNo(body)
                    if (refNo != null) {
                        if (refNo in seenRefNos) continue
                        seenRefNos.add(refNo)
                    }

                    result.add(txn to body)
                }
            }
        } catch (_: Exception) {}

        // Keep transactions as-is (already deduped by SMS _id and ref no).
        // This avoids dropping legitimate rapid transactions in the same minute.
        return result
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * A valid bank SMS must contain at least 3 of these signals.
     * This prevents catching promotional/spam SMS with bank names.
     */
    private fun looksLikeBankSms(body: String): Boolean {
        val signals = listOf(
            "debited", "credited", "debit", "credit",
            "A/c", "a/c", "account", "acct",
            "INR", "Rs.", "Rs:", "Rs ", "₹",
            "UPI", "IMPS", "NEFT", "RTGS",
            "transaction", "txn",
            "balance", "Bal", "Avl",
            "ref no", "Ref No"
        )
        return signals.count { body.contains(it, ignoreCase = true) } >= 3
    }

    private fun parseTransaction(body: String, smsDbId: Long, smsDate: Long): Transaction? {
        // 1. Extract amount — mandatory
        val amountMatch = amountRegex.find(body) ?: return null
        val rawAmount = amountMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        val amount = rawAmount.replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0) return null

        // 2. Detect type — must have a clear debit OR credit signal
        val hasDebit  = debitKeywords.any  { body.contains(it, ignoreCase = true) }
        val hasCredit = creditKeywords.any { body.contains(it, ignoreCase = true) }

        // If both or neither signal is present, infer by contextual phrasing
        val type = when {
            hasDebit && !hasCredit  -> "debit"
            hasCredit && !hasDebit  -> "credit"
            hasDebit && hasCredit   -> {
                // Ambiguous — check which keyword appears first in the SMS
                val firstDebit  = debitKeywords.mapNotNull  { kw -> body.indexOf(kw, ignoreCase = true).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
                val firstCredit = creditKeywords.mapNotNull { kw -> body.indexOf(kw, ignoreCase = true).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
                if (firstCredit < firstDebit) "credit" else "debit"
            }
            else -> inferTxnType(body) ?: return null
        }

        // 3. Build timestamp from SMS body (date + time)
        val txnDate  = buildTimestamp(body, smsDate)

        // 4. Extract merchant name
        val merchant = extractMerchant(body)

        // 5. Classify — debit: minimal rules; credit: income-style (no expense learning)
        val category = if (type == "credit") {
            CategoryClassifier.classifyCredit(merchant, body)
        } else {
            CategoryClassifier.classifyForSmsInbox(merchant, body)
        }

        val refDigits = extractRefNoFromText(body)?.filter { it.isDigit() }.orEmpty()

        return Transaction(
            id        = "sms_$smsDbId",
            amount    = amount,
            type      = type,
            merchant  = merchant,
            merchantKey = MerchantNormalizer.looseKey(merchant),
            category  = category,
            timestamp = txnDate,
            bankRefNo = refDigits
        )
    }

    /** Extract bank reference number from SMS or statement text (shared with PDF ↔ SMS matching). */
    fun extractRefNoFromText(text: String): String? =
        refRegex.find(text)?.groupValues?.getOrNull(1)

    /** Extract bank reference number for deduplication. */
    private fun extractRefNo(body: String): String? = extractRefNoFromText(body)

    /**
     * Extracts date + time from the SMS body.
     * Uses date found in text; merges with time if present; falls back to SMS receive time.
     */
    private fun buildTimestamp(body: String, smsDate: Long): Long {
        val dateMs = extractDate(body) ?: return smsDate

        val timeMatch = timeRegex.find(body)
        if (timeMatch != null) {
            val timePart = timeMatch.groupValues[1]
            val ampm     = timeMatch.groupValues[2]
            val pattern  = if (ampm.isNotBlank()) "hh:mm a" else "HH:mm"
            return try {
                val tFmt  = SimpleDateFormat(pattern, Locale.ENGLISH)
                val tDate = tFmt.parse("$timePart ${ampm.uppercase()}".trim()) ?: return dateMs
                val cal  = Calendar.getInstance().apply { timeInMillis = dateMs }
                val tCal = Calendar.getInstance().apply { timeInMillis = tDate.time }
                cal.set(Calendar.HOUR_OF_DAY, tCal.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, tCal.get(Calendar.MINUTE))
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis
            } catch (_: Exception) { dateMs }
        }
        return dateMs
    }

    private fun extractDate(body: String): Long? {
        for ((format, regex) in datePatterns) {
            val match = regex.find(body) ?: continue
            return try {
                SimpleDateFormat(format, Locale.ENGLISH).parse(match.groupValues[1])?.time
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun extractMerchant(body: String): String {
        for (pattern in merchantPatterns) {
            val candidate = pattern.find(body)?.groupValues?.getOrNull(1)?.trim() ?: continue
            // Skip candidates that are just noise words
            val noise = setOf("mob bk", "net bk", "atm", "ref", "avl bal", "bal", "on")
            if (candidate.length >= 3 && candidate.lowercase() !in noise) {
                return candidate.take(30)
            }
        }
        return "Bank Transaction"
    }

    /**
     * Fallback classifier for messages that do not explicitly contain
     * "debited/credited" but are still transaction alerts.
     */
    private fun inferTxnType(body: String): String? {
        val text = body.lowercase()

        // Explicit money-in patterns
        if (Regex("""\b(?:received|recv|credited\s+to|money\s+received|upi\s+collect)\b""")
                .containsMatchIn(text)
        ) return "credit"

        // Explicit money-out patterns including SBI-style:
        // "UPI Txn of Rs... frm ... A/c ... is successful"
        if (Regex("""\b(?:paid|sent|txn\s+of|upi\s+txn|payment|purchase|withdrawn|deducted)\b""")
                .containsMatchIn(text) &&
            Regex("""\b(?:from|frm|a/c|account)\b""").containsMatchIn(text)
        ) return "debit"

        return null
    }
}
