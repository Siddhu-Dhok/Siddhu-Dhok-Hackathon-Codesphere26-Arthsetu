package com.example.team_arthsetu.model

data class Transaction(
    val id: String = "",
    val userId: String = "",
    val amount: Double = 0.0,
    val category: String = "Other",
    val type: String = "debit",       // "debit" | "credit"
    val merchant: String = "",
    /** [MerchantNormalizer.looseKey]: letters+digits identity for bulk match / variants. */
    val merchantKey: String = "",
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** "sms" when ingested from bank SMS; empty for legacy / manual entries */
    val source: String = "",
    /**
     * Bank reference / UTR / IMPS ref (digits only when parsed). Used for dedupe (`sms_ref_*` doc id)
     * and PDF ↔ SMS matching when raw SMS body is not available from Firestore.
     */
    val bankRefNo: String = ""
) {
    /** Firestore / SMS may use any casing; risk and totals must match expense filters. */
    fun isDebit(): Boolean = type.equals("debit", ignoreCase = true)

    fun isCredit(): Boolean = type.equals("credit", ignoreCase = true)

    companion object {
        /**
         * Firestore document id for SMS transactions: stable per bank reference when present,
         * otherwise [fallbackId] (e.g. hash from [com.example.team_arthsetu.utils.TransactionIdFactory]).
         */
        fun smsFirestoreDocumentId(bankRefNo: String, fallbackId: String): String {
            val digits = bankRefNo.filter { it.isDigit() }
            return if (digits.isNotBlank()) "sms_ref_$digits" else fallbackId
        }
    }
}
