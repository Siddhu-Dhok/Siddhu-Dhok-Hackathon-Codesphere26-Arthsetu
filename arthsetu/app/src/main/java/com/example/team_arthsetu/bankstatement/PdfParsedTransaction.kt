package com.example.team_arthsetu.bankstatement

/**
 * Structured row produced from PDF text (before app-specific [StatementTransaction] / categorisation).
 * Maps 1:1 with the user-facing “transaction line” from the statement.
 */
data class PdfParsedTransaction(
    val date: String,
    val description: String,
    val amount: Double,
    val type: String,
    val balance: Double?,
    val extraNarration: String = "",
    val refNo: String = ""
) {
    fun toStatementTransaction(category: String = "Uncategorized"): StatementTransaction =
        StatementTransaction(
            merchant = description,
            amount = amount,
            type = type,
            date = date,
            balance = balance,
            category = category,
            narration = extraNarration,
            refNo = refNo
        )

    companion object {
        fun fromStatement(st: StatementTransaction): PdfParsedTransaction =
            PdfParsedTransaction(
                date = st.date,
                description = st.merchant,
                amount = st.amount,
                type = st.type,
                balance = st.balance,
                extraNarration = st.narration,
                refNo = st.refNo
            )
    }
}
