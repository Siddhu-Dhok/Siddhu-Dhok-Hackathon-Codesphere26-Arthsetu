package com.example.team_arthsetu.bankstatement

import com.example.team_arthsetu.model.Transaction

/** Rows for the bank-statement screen: PDF lines + optional SMS reconciliation section. */
sealed class BankStatementRowItem {
    data class SectionHeader(val title: String) : BankStatementRowItem()
    data class FromPdf(
        val transaction: StatementTransaction,
        val matchedBankSms: Boolean,
        val addedToExpenses: Boolean = false,
        /** Same ref as an existing SMS-saved expense — no need to add again. */
        val inExpenseBySmsRef: Boolean = false
    ) : BankStatementRowItem()

    data class UnmatchedSmsDebit(val transaction: Transaction) : BankStatementRowItem()

    /** Parsed inbox debit SMS that is not yet in Firestore expenses. */
    data class SmsInboxNotInExpenses(val transaction: Transaction, val rawBody: String) : BankStatementRowItem()
}
