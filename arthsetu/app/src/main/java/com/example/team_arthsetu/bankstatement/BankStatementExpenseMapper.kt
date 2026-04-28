package com.example.team_arthsetu.bankstatement

import android.content.Context
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.utils.CategoryResolver
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.MerchantNormalizer

object BankStatementExpenseMapper {

    suspend fun statementToExpense(context: Context, st: StatementTransaction): Transaction {
        val txnType =
            if (st.type == StatementTransaction.TYPE_CREDIT) Constants.CREDIT else Constants.DEBIT
        val bodyForCat = listOf(st.merchant, st.narration)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        val category = CategoryResolver.resolve(
            context,
            st.merchant,
            bodyForCat,
            txnType
        )
        val ts = StatementDateUtils.parseToMillis(st.date) ?: System.currentTimeMillis()
        val note = if (st.narration.isNotBlank()) {
            "${st.narration.take(200)} · Bank PDF"
        } else {
            "Bank statement PDF"
        }
        val refDigits = st.refNo.filter { it.isDigit() }
        return Transaction(
            id = "",
            amount = st.amount,
            category = category,
            type = txnType,
            merchant = st.merchant.trim().uppercase(),
            merchantKey = MerchantNormalizer.looseKey(st.merchant),
            note = note,
            timestamp = ts,
            source = Constants.TXN_SOURCE_BANK_PDF,
            bankRefNo = refDigits
        )
    }
}
