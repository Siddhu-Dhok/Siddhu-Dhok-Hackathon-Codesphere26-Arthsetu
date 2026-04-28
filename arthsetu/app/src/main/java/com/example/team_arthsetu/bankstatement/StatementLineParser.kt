package com.example.team_arthsetu.bankstatement

import android.util.Log
import com.example.team_arthsetu.utils.MerchantNormalizer
import com.example.team_arthsetu.utils.SmsParser
import java.util.Locale
import kotlin.math.abs

/**
 * Parses plain text from bank statement PDFs (PDFBox):
 * - Tab-separated lines (rare in PDFs) and multi-line transaction blocks
 * - Amount/date/merchant/debit-credit and optional balance
 */
object StatementLineParser {

    private const val TAG = "BankStmtParse"

    private val dateSlashRegex = Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""")
    private val dateDashRegex = Regex("""\b\d{1,2}-\d{1,2}-\d{2,4}\b""")
    private val dateMonthRegex = Regex("""\b\d{1,2}\s+[A-Za-z]{3}(?:\s+\d{2,4})?\b""")
    private val isoDateRegex = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
    /** Strict amount: `₹?\\d+\\.\\d{2}` (comma groups allowed). */
    private val amountStrict2dpRegex =
        Regex("""(?:₹\s*)?(\d+(?:,\d{2,3})*\.\d{2})""")
    /** Rs/₹/INR + amount (avoids treating dd/mm/yy fragments as money). */
    private val amountWithCurrencyPrefix = Regex(
        """(?:₹\s*|Rs\.?\s*|INR\s+)(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
    /** e.g. 1,23,456.78 without currency prefix */
    private val amountIndianCommaGrouped = Regex("""\b(\d{1,3}(?:,\d{2,3})+\.\d{1,2})\b""")
    /** Plain 2dp when integer part is wide enough to avoid dd in dates (e.g. 500.00 ok; 12.00 risky). */
    private val amountPlain2dpLarge = Regex("""\b(\d{3,}(?:,\d{3})*\.\d{2})\b""")
    /** 2–3 digit rupee amounts (e.g. 50.00) when not confused with year tokens. */
    private val amountSmall2dp = Regex("""\b(\d{2,3}\.\d{2})\b""")
    private val drCrRegex = Regex("""\b(DR|CR|DEBIT|CREDIT)\b""", RegexOption.IGNORE_CASE)

    private val blockIgnoreRegex = Regex(
        """\b(BALANCE|KYC|STATEMENT|ACCOUNT|A/C|AVAILABLE BAL|OPENING|CLOSING|TOTAL|IFSC|BRANCH|CHEQUE|REFERENCE|REFERENCES)\b""",
        RegexOption.IGNORE_CASE
    )

    // legacy/general parser patterns (used for tabular XLSX rows only)
    private val dateRegex = Regex(
        """\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{2}-[A-Za-z]{3}-\d{2,4}|\d{4}-\d{2}-\d{2})\b"""
    )
    private val amountRegex = Regex(
        """(?:₹|Rs\.?\s*|INR\s*)?([\d,]+(?:\.\d{1,2})?)(?!\d)""",
        RegexOption.IGNORE_CASE
    )
    private val likelyMerchantNoise = Regex("""\b(UPI|IMPS|NEFT|RTGS|REF|UTR|TXN|TRXN|PAYMENT|PAID|MOBILE|BANKING)\b""", RegexOption.IGNORE_CASE)

    private val amountBalanceRejectRegex = Regex(
        """\b(BALANCE|AVAILABLE BAL|AVAILABLE|OPENING|CLOSING|LEDGER|CLOSING BAL|BALANCE B/F)\b""",
        RegexOption.IGNORE_CASE
    )

    private val chequeOrReferenceRejectRegex =
        Regex("""\b(CHEQUE|REFERENCE|REFERENCES)\b""", RegexOption.IGNORE_CASE)

    private val merchantRejectRegex =
        Regex("""\b(CHEQUE|REFERENCE|REFERENCES|STATEMENT|BALANCE|ACCOUNT|KYC)\b""", RegexOption.IGNORE_CASE)

    fun parseAll(text: String): List<StatementTransaction> {
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return emptyList()

        val output = mutableListOf<StatementTransaction>()

        // Tab-separated rows (some PDF exports preserve column tabs)
        for (line in rawLines) {
            if (line.contains('\t')) {
                parseTabularLine(line)?.let { output.add(it) }
            }
        }

        // Multi-line blocks (non-tab lines)
        val ocrLines = rawLines.filter { !it.contains('\t') && isMeaningfulLine(it) }
        val blocks = groupLinesIntoTransactionBlocks(ocrLines)
        var previousBalance: Double? = null
        for (block in blocks) {
            Log.d(TAG, "BLOCK: ${block.joinToString(" | ")}")
            val txn = extractTransactionFromBlock(block, previousBalance)
            if (txn == null) {
                Log.d(TAG, "REJECTED BLOCK: ${block.joinToString(" | ")}")
                continue
            }
            Log.d(TAG, "FINAL TRANSACTION: $txn")
            if (txn.balance != null) previousBalance = txn.balance
            output.add(txn)
        }

        val merged = output.toMutableList()
        val seen = merged.map { dedupeKey(it) }.toMutableSet()
        for (line in rawLines) {
            if (line.contains('\t')) continue
            if (!isMeaningfulLine(line)) continue
            if (extractDateFromLine(line) == null) continue
            if (!containsTxnAmount(line)) continue
            val one = extractTransactionFromBlock(listOf(line), previousBalance = null) ?: continue
            val k = dedupeKey(one)
            if (k !in seen) {
                seen.add(k)
                merged.add(one)
            }
        }

        return merged.distinctBy { dedupeKey(it) }
    }

    private fun dedupeKey(t: StatementTransaction) =
        "${t.date}|${t.amount}|${t.type}|${t.merchant}"

    /**
     * Group 3–5 lines as one transaction when a DATE line has a nearby AMOUNT.
     */
    private fun groupLinesIntoTransactionBlocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var i = 0
        while (i < lines.size) {
            if (looksLikeTransactionAnchor(lines, i)) {
                val start = maxOf(0, i - 2)
                val end = minOf(lines.lastIndex, i + 2) // keep block size ~3-5 lines
                val block = lines.subList(start, end + 1).filter { it.isNotBlank() }
                if (block.isNotEmpty()) blocks += block
                // Move ahead slowly so nearby anchors are not skipped.
                i += 1
            } else {
                i++
            }
        }
        return blocks
    }

    private fun looksLikeTransactionAnchor(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        // Anchor on any common statement date format; require a nearby strict amount.
        if (extractDateFromLine(line) == null) return false
        val from = maxOf(0, index - 3)
        val to = minOf(lines.lastIndex, index + 3)
        for (k in from..to) {
            if (containsTxnAmount(lines[k])) return true
        }
        return false
    }

    /** First date token found on the line (formats supported by [StatementDateUtils.parseToMillis]). */
    private fun extractDateFromLine(line: String): String? {
        dateSlashRegex.find(line)?.value?.let { return it }
        dateDashRegex.find(line)?.value?.let { return it }
        isoDateRegex.find(line)?.value?.let { return it }
        dateMonthRegex.find(line)?.value?.let { return it }
        return null
    }

    private fun extractTransactionFromBlock(block: List<String>, previousBalance: Double?): StatementTransaction? {
        val joined = block.joinToString(" ")
        val narration = block.joinToString(" | ").take(500)

        val dateLine = block.firstOrNull { extractDateFromLine(it) != null } ?: return null
        val date = extractDateFromLine(dateLine) ?: return null
        Log.d(TAG, "DATE FOUND: $date")

        val candidateLines = block
            .filter { !amountBalanceRejectRegex.containsMatchIn(it) }
            .filter { !it.contains(blockIgnoreRegex) }
            .filter { containsTxnAmount(it) }
        if (candidateLines.isEmpty()) return null

        val amounts = candidateLines.flatMap { parseAmountsFromLine(it) }.filter { it > 0.01 }
        if (amounts.isEmpty()) return null

        val distinctSorted = amounts.distinct().sorted()
        val amount = pickTxnAmount(block, distinctSorted) ?: return null
        Log.d(TAG, "AMOUNT FOUND: $amount (candidates=$distinctSorted)")

        val currentBalance = extractBalance(block)
        if (currentBalance != null) Log.d(TAG, "BALANCE FOUND: $currentBalance")

        // Merchant: single best line in block (NOT assume first line)
        var merchantRaw = selectMerchantLineStrict(block)
        if (merchantRaw.isBlank()) {
            merchantRaw = cleanMerchantStrict(deriveMerchantFromJoined(joined))
        }
        Log.d(TAG, "MERCHANT RAW: $merchantRaw")
        var merchantClean = cleanMerchantStrict(merchantRaw)
        if (merchantClean.length !in 3..90) {
            merchantClean = cleanMerchantStrict(deriveMerchantFromJoined(joined))
        }
        Log.d(TAG, "MERCHANT CLEAN: $merchantClean")

        if (merchantClean.length !in 3..90) return null
        if (merchantRejectRegex.containsMatchIn(merchantClean)) return null

        val merchantFinal = MerchantNormalizer.normalize(merchantClean)
        if (merchantFinal.length < 3) return null

        val typeResult = detectTypeStrict(block, joined, currentBalance, previousBalance = previousBalance)
        Log.d(TAG, "TYPE DETECTION SOURCE: ${typeResult.source}")
        Log.d(TAG, "TYPE: ${typeResult.type}")

        val refDigits = SmsParser.extractRefNoFromText(joined)?.filter { it.isDigit() }.orEmpty()

        return StatementTransaction(
            merchant = merchantFinal,
            amount = amount,
            type = typeResult.type,
            date = date,
            balance = currentBalance,
            narration = narration,
            refNo = refDigits
        )
    }

    private fun isMeaningfulLine(line: String): Boolean {
        if (line.length < 2) return false
        if (line.contains(blockIgnoreRegex)) return false
        return true
    }

    private fun containsAmountStrict(line: String): Boolean =
        amountStrict2dpRegex.containsMatchIn(line)

    private fun containsTxnAmount(line: String): Boolean =
        amountStrict2dpRegex.containsMatchIn(line) ||
            amountWithCurrencyPrefix.containsMatchIn(line) ||
            amountIndianCommaGrouped.containsMatchIn(line) ||
            amountPlain2dpLarge.containsMatchIn(line) ||
            amountSmall2dp.containsMatchIn(line)

    private fun parseAmountsFromLine(line: String): List<Double> {
        val found = mutableListOf<Double>()
        fun addAll(r: Regex) {
            r.findAll(line).forEach { m ->
                m.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                    ?.takeIf { it > 0.01 }?.let { found.add(it) }
            }
        }
        addAll(amountStrict2dpRegex)
        addAll(amountWithCurrencyPrefix)
        addAll(amountIndianCommaGrouped)
        addAll(amountPlain2dpLarge)
        addAll(amountSmall2dp)
        return found
    }

    private fun stripAmountPatterns(s: String): String {
        var t = amountStrict2dpRegex.replace(s, " ")
        t = amountWithCurrencyPrefix.replace(t, " ")
        t = amountIndianCommaGrouped.replace(t, " ")
        t = amountPlain2dpLarge.replace(t, " ")
        t = amountSmall2dp.replace(t, " ")
        return t
    }

    /**
     * When a block has multiple numbers (withdrawal + balance), prefer the transaction amount.
     */
    private fun pickTxnAmount(block: List<String>, distinctSorted: List<Double>): Double? {
        if (distinctSorted.isEmpty()) return null
        if (distinctSorted.size == 1) return distinctSorted.first()

        val bal = extractBalance(block)
        if (bal != null) {
            val notBal = distinctSorted.filter { abs(it - bal) >= 0.02 }
            when {
                notBal.size == 1 -> return notBal.first()
                notBal.isNotEmpty() -> {
                    val dateLine = block.firstOrNull { extractDateFromLine(it) != null } ?: return notBal.minOrNull()
                    val onDate = parseAmountsFromLine(dateLine).toSet()
                    val onDateMatches = notBal.filter { amt ->
                        onDate.any { od -> abs(amt - od) < 0.02 }
                    }
                    if (onDateMatches.size == 1) return onDateMatches.first()
                    return notBal.minOrNull()
                }
                else -> return distinctSorted.minOrNull()
            }
        }

        val dateLine = block.firstOrNull { extractDateFromLine(it) != null } ?: return distinctSorted.minOrNull()
        val onDate = parseAmountsFromLine(dateLine)
        val fromDateLine = distinctSorted.filter { amt ->
            onDate.any { od -> abs(amt - od) < 0.02 }
        }
        if (fromDateLine.size == 1) return fromDateLine.first()
        return distinctSorted.minOrNull()
    }

    private fun cleanMerchant(s: String): String {
        return s
            .replace(Regex("""\bUPI\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(YESB|SBIN|IPOS|ATM|POS)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(likelyMerchantNoise, " ")
            .replace("/", " ")
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun deriveMerchantFromJoined(joined: String): String {
        var s = joined
        s = dateSlashRegex.replace(s, " ")
        s = dateDashRegex.replace(s, " ")
        s = dateMonthRegex.replace(s, " ")
        s = isoDateRegex.replace(s, " ")
        s = stripAmountPatterns(s)
        s = drCrRegex.replace(s, " ")
        s = likelyMerchantNoise.replace(s, " ")
        s = s.replace(blockIgnoreRegex, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    private fun selectMerchantLineStrict(block: List<String>): String {
        val candidates = mutableListOf<String>()
        for (line in block) {
            if (line.isBlank() || line.contains(blockIgnoreRegex) || merchantRejectRegex.containsMatchIn(line)) continue
            if (amountBalanceRejectRegex.containsMatchIn(line)) continue
            if (chequeOrReferenceRejectRegex.containsMatchIn(line) &&
                !Regex("""UPI|NEFT|IMPS|RTGS""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            ) {
                continue
            }
            var work = line
            extractDateFromLine(line)?.let { tok -> work = work.replace(tok, " ") }
            work = stripAmountPatterns(work)
            work = work.replace(Regex("""\s+"""), " ").trim()
            if (work.length < 3 || !work.any { it.isLetter() }) continue
            val c = cleanMerchantStrict(work)
            if (c.length in 3..60) candidates += c
        }
        if (candidates.isEmpty()) return ""
        return candidates.distinct().maxByOrNull { it.length } ?: ""
    }

    private fun cleanMerchantStrict(s: String): String {
        val cleaned = s
            .replace(Regex("""\b(UPI|YESB|SBIN|IPOS)\b""", RegexOption.IGNORE_CASE), " ")
            .replace("/", " ")
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Keep only first 2–3 meaningful words
        val parts = cleaned.split(' ').filter { it.isNotBlank() }
        val keep = parts.take(3)
        return keep.joinToString(" ").trim()
    }

    private data class TypeResult(val type: String, val source: String)

    private fun detectTypeStrict(
        block: List<String>,
        joined: String,
        currentBalance: Double?,
        previousBalance: Double?
    ): TypeResult {
        val u = joined.uppercase(Locale.US)

        val hasExplicitDr = Regex(
            """\bDR\.?\b|\bDEBIT\b|WITHDRAW|ATM\s|POS\s|WDL\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(joined)
        val hasExplicitCr = Regex(
            """\bCR\.?\b|\bCREDIT\b|DEPOSIT|RECEIVED|SALARY|REFUND|CASHBACK|REVERSAL""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(joined)
        if (hasExplicitCr && !hasExplicitDr) return TypeResult(StatementTransaction.TYPE_CREDIT, "dr_cr_explicit")
        if (hasExplicitDr && !hasExplicitCr) return TypeResult(StatementTransaction.TYPE_DEBIT, "dr_cr_explicit")

        val creditKeywords = listOf(
            " CR ", " CREDIT ", " RECEIVED ", " SALARY ", " REFUND ", " DEPOSIT ", " CASHBACK ",
            "UPI CREDIT", "CREDIT BY", "NEFT CR", "IMPS CR", "RTGS CR", "INCOMING", "CREDITED"
        )
        val debitKeywords = listOf(
            " DR ", " DEBIT ", " WITHDRAWN ", " PURCHASE ", " POS/", " POS ", " ATM ",
            " UPI/", "NEFT-", "IMPS-", "RTGS-", " PAID ", " CHARGES ", " FEE "
        )
        if (creditKeywords.any { u.contains(it) }) return TypeResult(StatementTransaction.TYPE_CREDIT, "keyword")
        if (debitKeywords.any { u.contains(it) }) return TypeResult(StatementTransaction.TYPE_DEBIT, "keyword")

        if (u.contains("UPI")) {
            if (u.contains("RECEIVED") || u.contains("FROM ") && u.contains("CREDIT")) {
                return TypeResult(StatementTransaction.TYPE_CREDIT, "upi_credit")
            }
            return TypeResult(StatementTransaction.TYPE_DEBIT, "upi_default_debit")
        }

        val context = block.joinToString(" ").uppercase(Locale.US)
        if (Regex("""\bTO\b""").containsMatchIn(context)) return TypeResult(StatementTransaction.TYPE_DEBIT, "context_to")
        if (Regex("""\bFROM\b""").containsMatchIn(context)) return TypeResult(StatementTransaction.TYPE_CREDIT, "context_from")

        if (previousBalance != null && currentBalance != null) {
            if (currentBalance < previousBalance) return TypeResult(StatementTransaction.TYPE_DEBIT, "balance_delta")
            if (currentBalance > previousBalance) return TypeResult(StatementTransaction.TYPE_CREDIT, "balance_delta")
        }

        return TypeResult(StatementTransaction.TYPE_DEBIT, "default_debit")
    }

    private fun extractBalance(block: List<String>): Double? {
        val balanceLine = block.firstOrNull { amountBalanceRejectRegex.containsMatchIn(it) } ?: return null
        val amounts = parseAmountsFromLine(balanceLine)
        if (amounts.isNotEmpty()) return amounts.last()
        val strict = amountStrict2dpRegex.find(balanceLine)
        return strict?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
    }

    /** XLSX rows joined with tab */
    private fun parseTabularLine(line: String): StatementTransaction? {
        val cols = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (cols.size < 2) return null

        var dateStr: String? = null
        var dateIdx = -1
        cols.forEachIndexed { i, c ->
            if (dateStr == null) extractDate(c)?.let { dateStr = it; dateIdx = i }
        }
        val resolvedDate = dateStr ?: return null

        val amounts = cols.mapIndexedNotNull { i, c ->
            if (i == dateIdx) null else parseAmountInCell(c)
        }
        if (amounts.isEmpty()) return null

        val lower = line.lowercase(Locale.US)
        val type = inferType(lower, cols)

        val amount = when (type) {
            StatementTransaction.TYPE_CREDIT -> amounts.lastOrNull() ?: amounts.first()
            else -> amounts.first()
        }

        val merchant = cols.filterIndexed { i, _ -> i != dateIdx }
            .joinToString(" ")
            .replace(dateRegex, "")
            .replace(amountRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
            .ifBlank { "Transaction" }

        val refDigits = SmsParser.extractRefNoFromText(line)?.filter { it.isDigit() }.orEmpty()

        return StatementTransaction(
            merchant = MerchantNormalizer.normalize(merchant),
            amount = amount,
            type = type,
            date = resolvedDate,
            balance = null,
            narration = line.take(500),
            refNo = refDigits
        )
    }

    private fun extractDate(s: String): String? {
        val m = dateRegex.find(s) ?: return null
        return m.groupValues[1]
    }

    private fun parseAmountInCell(s: String): Double? {
        val m = amountRegex.find(s) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun inferType(lowerLine: String, cols: List<String>): String {
        val joined = (lowerLine + " " + cols.joinToString(" ").lowercase(Locale.US))
        if (joined.contains(" cr") || joined.contains(" credit")) return StatementTransaction.TYPE_CREDIT
        if (joined.contains(" dr") || joined.contains(" debit")) return StatementTransaction.TYPE_DEBIT
        return StatementTransaction.TYPE_DEBIT
    }
}
