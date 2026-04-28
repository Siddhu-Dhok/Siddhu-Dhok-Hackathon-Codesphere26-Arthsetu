package com.example.team_arthsetu.bankstatement

/**
 * One row parsed from a bank statement PDF (PDFBox text).
 * [type] is [TYPE_DEBIT] or [TYPE_CREDIT] for display; map to app [Constants] when categorising.
 * [narration] is the raw block text from the statement (truncated) for reference / UPI details.
 */
data class StatementTransaction(
    val merchant: String,
    val amount: Double,
    val type: String,
    val date: String,
    val balance: Double?,
    val category: String = "Uncategorized",
    val narration: String = "",
    /** Bank ref / UTR from statement text (digits), for matching SMS [com.example.team_arthsetu.model.Transaction.bankRefNo]. */
    val refNo: String = ""
) {
    companion object {
        const val TYPE_DEBIT = "DEBIT"
        const val TYPE_CREDIT = "CREDIT"

        /** Stable key for UI dedupe / “already added to expenses” (aligned with [StatementLineParser] dedupe). */
        fun identityKey(t: StatementTransaction): String =
            "${t.date}|${t.amount}|${t.type}|${t.merchant}"
    }
}
