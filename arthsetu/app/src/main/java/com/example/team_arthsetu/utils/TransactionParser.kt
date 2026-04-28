package com.example.team_arthsetu.utils

import android.util.Log
import com.example.team_arthsetu.model.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Single entry point for parsing bank SMS into [Transaction] rows.
 * Delegates core rules to [SmsParser], then enriches with bank/mode detection,
 * improved amount/merchant/date/mode extraction, and UNKNOWN fallback.
 */
object TransactionParser {

    private const val TAG = "TransactionParser"
    private const val UNKNOWN_MERCHANT = "UNKNOWN"
    private const val NOTE_KEY_BANK = "bank"
    private const val NOTE_KEY_MODE = "mode"
    private const val NOTE_KEY_STATUS = "parse"

    // ── Bank detection (SBI, Bank of Maharashtra, Union Bank) ───────────────
    private val bankMatchers: List<Pair<String, Regex>> = listOf(
        "SBI" to Regex(
            """(?:^|\s)(?:State\s+Bank\s+of\s+India|SBI|SBIN)(?:\s|$|[^A-Za-z])""",
            RegexOption.IGNORE_CASE
        ),
        "Bank of Maharashtra" to Regex(
            """(?:^|\s)(?:Bank\s+of\s+Maharashtra|BOM|MAHB)(?:\s|$|[^A-Za-z])""",
            RegexOption.IGNORE_CASE
        ),
        "Union Bank" to Regex(
            """(?:^|\s)(?:Union\s+Bank(?:\s+of\s+India)?|UBIN|unionbank)(?:\s|$|[^A-Za-z])""",
            RegexOption.IGNORE_CASE
        )
    )

    // ── Amount: Rs., INR, ₹, plain numbers near currency tokens ─────────────
    private val amountPatterns: List<Regex> = listOf(
        Regex("""(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([\d,]+(?:\.\d{1,2})?)\s*(?:INR|Rs\.?|₹)""", RegexOption.IGNORE_CASE),
        Regex("""(?:amount|amt|txn\s+amt)[:\s]+(?:INR|Rs\.?|₹)?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:debited|credited)\s+by\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    // ── Merchant: to / trf to / from (supplements [SmsParser]) ──────────────
    private val merchantFromPatterns: List<Regex> = listOf(
        Regex(
            """(?:trf\s+to|transfer\s+to)\s+([A-Za-z0-9 &'.@/\-]{3,40}?)(?=\s+(?:Ref(?:no)?|If\s+not|on|date)\b|[.;\n]|$)""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""(?:^|[\s,])(?:to)\s+([A-Z][A-Za-z0-9 &'.@/\-]{2,35})(?:\s+on|\s+Ref|\.|;|\n|Avl|Bal|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:trf\s+to)\s+([A-Za-z0-9 &'.@/\-]{3,40})(?:\s+Ref(?:no)?|\.|;|\n|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[\s,])(?:from|frm)\s+([A-Za-z0-9 &'.@/\-]{3,40})(?:\s+A/c|\s+on|\s+Ref|\.|;|\n|$)""", RegexOption.IGNORE_CASE)
    )

    // ── Date: dd-MMM-yy, ddMMMyy, dd-MM-yyyy (explicit layer on top of SmsParser) ─
    private val extraDatePatterns: List<Pair<String, Regex>> = listOf(
        "dd-MMM-yy" to Regex("""\b(\d{2}-[A-Za-z]{3}-\d{2})\b"""),
        "ddMMMyy" to Regex("""\b(\d{2}[A-Za-z]{3}\d{2})\b"""),
        "dd-MM-yyyy" to Regex("""\b(\d{2}-\d{2}-\d{4})\b""")
    )

    private val timeRegex = Regex(
        """\b(\d{1,2}:\d{2}(?::\d{2})?)\s*(AM|PM)?\b""",
        RegexOption.IGNORE_CASE
    )

    // ── Mode: UPI, ATM, Mobile Banking ────────────────────────────────────────
    private fun detectMode(body: String): String? {
        val t = body.lowercase(Locale.US)
        return when {
            Regex("""\b(?:upi|\/p2a\/|@paytm|@ybl|@okaxis|@oksbi|@okhdfcbank)\b""").containsMatchIn(t) -> "UPI"
            Regex("""\b(?:atm|cash\s*withdrawal|w\/d\s+at\s+atm)\b""").containsMatchIn(t) -> "ATM"
            Regex("""\b(?:mobile\s*bank|mob\s*bk|net\s*bk|internet\s*bank|imobile|yono)\b""").containsMatchIn(t) -> "Mobile Banking"
            else -> null
        }
    }

    fun isBankTransaction(body: String): Boolean {
        if (SmsParser.isBankTransactionSms(body)) {
            Log.d(TAG, "isBankTransaction=true (SmsParser) len=${body.length}")
            return true
        }
        if (detectBank(body) != null && hasLooseBankSignals(body)) {
            Log.d(TAG, "isBankTransaction=true (bank hint + signals)")
            return true
        }
        Log.d(TAG, "isBankTransaction=false len=${body.length}")
        return false
    }

    private fun hasLooseBankSignals(body: String): Boolean {
        val t = body.lowercase(Locale.US)
        val hasMoney = extractAmountEnhanced(body) != null
        val hasVerb = t.contains("debited") || t.contains("credited") || t.contains("txn") ||
            t.contains("transaction") || t.contains("paid") || t.contains("sent")
        return hasMoney && hasVerb
    }

    fun detectBank(body: String): String? {
        for ((label, rx) in bankMatchers) {
            if (rx.containsMatchIn(body)) return label
        }
        return null
    }

    // Production: do not depend on SMS inbox lookups; parse from broadcast body + timestamp only.

    /**
     * Parses [body] into a transaction with amount, debit/credit type, and merchant.
     * On hard failure, returns a row with [UNKNOWN_MERCHANT] and [note] marking parse status.
     */
    fun parseToTransaction(body: String, smsDateMillis: Long): Transaction? {
        if (body.isBlank()) {
            Log.w(TAG, "parseToTransaction: invalid input")
            return null
        }

        val bank = detectBank(body)
        val mode = detectMode(body)

        val smsDbId = 0L
        var base = SmsParser.parseIncomingSms(body, smsDbId, smsDateMillis)
        if (base == null) {
            Log.w(TAG, "SmsParser returned null — attempting fallback")
            base = buildFallbackTransaction(body, smsDbId, smsDateMillis, bank, mode)
            if (base == null) {
                Log.e(TAG, "parse failed completely")
                return null
            }
        }

        val amountFixed = extractAmountEnhanced(body)?.takeIf { it > 0 } ?: base.amount
        if (amountFixed <= 0) {
            Log.w(TAG, "parse failed: non-positive amount")
            return null
        }

        val merchantFixed = extractMerchantEnhanced(body) ?: base.merchant
        val tsFixed = mergeTimestamp(body, base.timestamp)

        val type = base.type.lowercase(Locale.US)
        if (type != Constants.DEBIT && type != Constants.CREDIT) {
            Log.w(TAG, "parse failed: invalid type=${base.type}")
            return null
        }

        val note = buildNote(bank, mode, base.merchant == UNKNOWN_MERCHANT || merchantFixed == UNKNOWN_MERCHANT)

        val cleanedMerchant = MerchantNormalizer.normalize(merchantFixed.ifBlank { UNKNOWN_MERCHANT })
        val storedMerchant = CategoryClassifier.normalizeMerchantForStorage(
            cleanedMerchant.ifBlank { UNKNOWN_MERCHANT }
        )
        val refDigits = SmsParser.extractRefNoFromText(body)?.filter { it.isDigit() }.orEmpty()
        val merged = base.copy(
            amount = amountFixed,
            merchant = storedMerchant,
            merchantKey = MerchantNormalizer.looseKey(storedMerchant),
            timestamp = tsFixed,
            note = note,
            bankRefNo = refDigits
        )

        val normalizedBody = TransactionIdFactory.normalizeSmsBody(body)
        val hashId = TransactionIdFactory.documentId(
            normalizedBody,
            merged.amount,
            merged.timestamp,
            merged.merchant
        )
        val docId = Transaction.smsFirestoreDocumentId(refDigits, hashId)
        val withSource = merged.copy(id = docId, source = Constants.TXN_SOURCE_SMS)

        Log.i(
            TAG,
            "parse success transactionId=$docId amount=${withSource.amount} type=${withSource.type} " +
                "merchant=${withSource.merchant} bank=${bank ?: "-"} mode=${mode ?: "-"}"
        )
        return withSource
    }

    private fun buildNote(bank: String?, mode: String?, unknown: Boolean): String {
        val parts = mutableListOf<String>()
        bank?.let { parts.add("$NOTE_KEY_BANK=$it") }
        mode?.let { parts.add("$NOTE_KEY_MODE=$it") }
        if (unknown) parts.add("$NOTE_KEY_STATUS=UNKNOWN")
        return parts.joinToString("|")
    }

    private fun buildFallbackTransaction(
        body: String,
        smsDbId: Long,
        smsDateMillis: Long,
        bank: String?,
        mode: String?
    ): Transaction? {
        val amount = extractAmountEnhanced(body) ?: return null
        if (amount <= 0) return null

        val hasDebit = Regex("""\b(?:debited|debit|spent|paid|sent|dr\.?|deducted)\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val hasCredit = Regex("""\b(?:credited|credit|received|deposit|cr\.?|refund)\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val type = when {
            hasDebit && !hasCredit -> Constants.DEBIT
            hasCredit && !hasDebit -> Constants.CREDIT
            hasDebit && hasCredit -> Constants.DEBIT
            else -> inferTypeLoose(body) ?: Constants.DEBIT
        }

        val ts = mergeTimestamp(body, smsDateMillis)
        val norm = TransactionIdFactory.normalizeSmsBody(body)
        val refDigits = SmsParser.extractRefNoFromText(body)?.filter { it.isDigit() }.orEmpty()
        val hashId = TransactionIdFactory.documentId(norm, amount, ts, UNKNOWN_MERCHANT)
        val id = Transaction.smsFirestoreDocumentId(refDigits, hashId)
        return Transaction(
            id = id,
            amount = amount,
            type = type,
            merchant = UNKNOWN_MERCHANT,
            merchantKey = MerchantNormalizer.looseKey(UNKNOWN_MERCHANT),
            category = "Uncategorized",
            timestamp = ts,
            note = buildNote(bank, mode, unknown = true),
            bankRefNo = refDigits
        )
    }

    private fun inferTypeLoose(body: String): String? {
        val t = body.lowercase(Locale.US)
        if (t.contains("credit") && !t.contains("debited")) return Constants.CREDIT
        if (t.contains("debit") || t.contains("paid")) return Constants.DEBIT
        return null
    }

    private fun extractAmountEnhanced(body: String): Double? {
        for (rx in amountPatterns) {
            val m = rx.find(body) ?: continue
            val g = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            g.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    private fun extractMerchantEnhanced(body: String): String? {
        for (rx in merchantFromPatterns) {
            val m = rx.find(body) ?: continue
            val name = m.groupValues.getOrNull(1)?.trim() ?: continue
            val cleaned = name.trimEnd('.', ';')
            if (cleaned.length >= 3 && cleaned.lowercase(Locale.US) !in NOISE_MERCHANTS) {
                return cleaned.take(40)
            }
        }
        return null
    }

    private val NOISE_MERCHANTS = setOf("the", "and", "your", "our", "bank", "acct", "account")

    /**
     * Prefer explicit dates: dd-MMM-yy, ddMMMyy, dd-MM-yyyy; merge time if present.
     */
    private fun mergeTimestamp(body: String, parsedMs: Long): Long {
        var dateMs: Long? = null
        for ((fmt, rx) in extraDatePatterns) {
            val match = rx.find(body) ?: continue
            val raw = match.groupValues[1]
            dateMs = try {
                SimpleDateFormat(fmt, Locale.ENGLISH).parse(raw)?.time
            } catch (_: Exception) {
                null
            }
            if (dateMs != null) break
        }
        val baseDay = dateMs ?: parsedMs

        val timeMatch = timeRegex.find(body)
        if (timeMatch == null) return baseDay

        val timePart = timeMatch.groupValues[1]
        val ampm = timeMatch.groupValues[2]
        val pattern = if (ampm.isNotBlank()) "hh:mm a" else "HH:mm"
        return try {
            val tFmt = SimpleDateFormat(pattern, Locale.ENGLISH)
            val tDate = tFmt.parse("$timePart ${ampm.uppercase(Locale.US)}".trim()) ?: return baseDay
            val cal = Calendar.getInstance().apply { timeInMillis = baseDay }
            val tCal = Calendar.getInstance().apply { timeInMillis = tDate.time }
            cal.set(Calendar.HOUR_OF_DAY, tCal.get(Calendar.HOUR_OF_DAY))
            cal.set(Calendar.MINUTE, tCal.get(Calendar.MINUTE))
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) {
            baseDay
        }
    }
}
