package com.example.team_arthsetu.bankstatement

import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.MerchantNormalizer
import com.example.team_arthsetu.utils.SmsParser
import java.util.Calendar
import kotlin.math.abs

/**
 * Match SMS-ingested debits to PDF statement lines.
 *
 * 1. When both sides have a reference number and they match → same transaction (amount must match).
 * 2. Otherwise fall back to same calendar day + amount + merchant compatibility.
 */
object BankStatementSmsPdfMatcher {

    private fun normalizeRefDigits(ref: String): String = ref.filter { it.isDigit() }

    fun matchesSmsDebitToPdfRow(
        sms: Transaction,
        pdf: StatementTransaction,
        smsRawBody: String? = null
    ): Boolean {
        if (!sms.type.equals(Constants.DEBIT, ignoreCase = true)) return false
        if (pdf.type != StatementTransaction.TYPE_DEBIT) return false
        if (!amountClose(sms.amount, pdf.amount)) return false

        val pdfText = "${pdf.narration} ${pdf.merchant}"
        val smsText = when {
            !smsRawBody.isNullOrBlank() -> smsRawBody
            else -> "${sms.note} ${sms.merchant}"
        }
        val refPdf = normalizeRefDigits(pdf.refNo).takeIf { it.isNotBlank() }
            ?: SmsParser.extractRefNoFromText(pdfText)?.let(::normalizeRefDigits)
        val refSms = when {
            sms.bankRefNo.isNotBlank() -> normalizeRefDigits(sms.bankRefNo)
            else -> SmsParser.extractRefNoFromText(smsText)?.let(::normalizeRefDigits)
        }

        if (refPdf != null && refSms != null) {
            if (refPdf != refSms) return false
            return timestampMatchesPdfDay(sms.timestamp, pdf.date)
        }

        if (!timestampMatchesPdfDay(sms.timestamp, pdf.date)) return false
        return merchantCompatible(sms.merchant, pdf.merchant)
    }

    /** SMS debits (from expenses) that have no corresponding PDF debit row for this import. */
    fun unmatchedSmsDebits(
        smsTransactions: List<Transaction>,
        pdfRows: List<StatementTransaction>,
        smsIdToRawBody: Map<String, String> = emptyMap()
    ): List<Transaction> {
        val pdfDebits = pdfRows.filter { it.type == StatementTransaction.TYPE_DEBIT }
        return smsTransactions
            .filter { it.type.equals(Constants.DEBIT, ignoreCase = true) }
            .filter { isSmsSourcedExpense(it) }
            .filter { sms ->
                pdfDebits.none { pdf ->
                    matchesSmsDebitToPdfRow(sms, pdf, smsIdToRawBody[sms.id])
                }
            }
    }

    fun pdfRowMatchedSms(
        pdf: StatementTransaction,
        smsTransactions: List<Transaction>,
        smsIdToRawBody: Map<String, String> = emptyMap()
    ): Boolean {
        if (pdf.type != StatementTransaction.TYPE_DEBIT) return false
        return smsTransactions.any { matchesSmsDebitToPdfRow(it, pdf, smsIdToRawBody[it.id]) }
    }

    private fun isSmsSourcedExpense(t: Transaction): Boolean =
        t.source.equals(Constants.TXN_SOURCE_SMS, ignoreCase = true) ||
            t.id.startsWith("sms_")

    private fun amountClose(a: Double, b: Double): Boolean = abs(a - b) < 0.02

    /** SMS timestamp and PDF row date fall on the same calendar day (value date alignment). */
    private fun timestampMatchesPdfDay(smsTimestampMillis: Long, pdfDateStr: String): Boolean {
        val pdfMs = StatementDateUtils.parseToMillis(pdfDateStr) ?: return false
        val cSms = Calendar.getInstance().apply { timeInMillis = smsTimestampMillis }
        val cPdf = Calendar.getInstance().apply { timeInMillis = pdfMs }
        return cSms.get(Calendar.YEAR) == cPdf.get(Calendar.YEAR) &&
            cSms.get(Calendar.DAY_OF_YEAR) == cPdf.get(Calendar.DAY_OF_YEAR)
    }

    private fun merchantCompatible(smsMerchant: String, pdfMerchant: String): Boolean {
        val k1 = MerchantNormalizer.looseKey(smsMerchant)
        val k2 = MerchantNormalizer.looseKey(pdfMerchant)
        if (k1.isNotBlank() && k2.isNotBlank() && k1 == k2) return true
        val a = smsMerchant.uppercase().replace(Regex("""\s+"""), "")
        val b = pdfMerchant.uppercase().replace(Regex("""\s+"""), "")
        if (a.isBlank() || b.isBlank()) return true
        return a.contains(b) || b.contains(a)
    }
}

