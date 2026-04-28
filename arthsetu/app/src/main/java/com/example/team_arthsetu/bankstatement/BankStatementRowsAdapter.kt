package com.example.team_arthsetu.bankstatement

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Transaction
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BankStatementRowsAdapter(
    private val onAddPdfToExpenses: (StatementTransaction) -> Unit,
    private val onUnmatchedSmsCardClick: (Transaction) -> Unit,
    private val onInboxSmsCardClick: (Transaction) -> Unit,
    private val onAddInboxSmsToExpenses: (Transaction) -> Unit
) : ListAdapter<BankStatementRowItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BankStatementRowItem.SectionHeader -> VT_HEADER
        is BankStatementRowItem.FromPdf -> VT_PDF
        is BankStatementRowItem.UnmatchedSmsDebit -> VT_SMS_FIRESTORE
        is BankStatementRowItem.SmsInboxNotInExpenses -> VT_SMS_INBOX
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_HEADER -> HeaderVH(
                inf.inflate(R.layout.item_bank_statement_section, parent, false)
            )
            VT_PDF -> PdfVH(
                inf.inflate(R.layout.item_statement_transaction, parent, false)
            )
            VT_SMS_FIRESTORE, VT_SMS_INBOX -> SmsVH(
                inf.inflate(R.layout.item_unmatched_sms_statement, parent, false)
            )
            else -> error("unknown type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BankStatementRowItem.SectionHeader -> (holder as HeaderVH).bind(item.title)
            is BankStatementRowItem.FromPdf -> (holder as PdfVH).bind(item, onAddPdfToExpenses)
            is BankStatementRowItem.UnmatchedSmsDebit ->
                (holder as SmsVH).bindFirestoreUnmatched(item.transaction, onUnmatchedSmsCardClick)
            is BankStatementRowItem.SmsInboxNotInExpenses ->
                (holder as SmsVH).bindInboxPending(
                    item.transaction,
                    onInboxSmsCardClick,
                    onAddInboxSmsToExpenses
                )
        }
    }

    private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.tvSectionTitle)
        fun bind(t: String) {
            title.text = t
        }
    }

    private class PdfVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMerchant = view.findViewById<TextView>(R.id.tvMerchant)
        private val tvNarration = view.findViewById<TextView>(R.id.tvNarration)
        private val tvAmount = view.findViewById<TextView>(R.id.tvAmount)
        private val tvType = view.findViewById<TextView>(R.id.tvType)
        private val tvDate = view.findViewById<TextView>(R.id.tvDate)
        private val tvCategory = view.findViewById<TextView>(R.id.tvCategory)
        private val tvBalance = view.findViewById<TextView>(R.id.tvBalance)
        private val tvSmsMatched = view.findViewById<TextView>(R.id.tvSmsMatched)
        private val btnAdd = view.findViewById<MaterialButton>(R.id.btnAddToExpenses)

        fun bind(
            row: BankStatementRowItem.FromPdf,
            onAdd: (StatementTransaction) -> Unit
        ) {
            val t = row.transaction
            val ctx = itemView.context
            val isCredit = t.type == StatementTransaction.TYPE_CREDIT
            tvMerchant.text = t.merchant
            tvAmount.text = formatRupee(t.amount)
            tvDate.text = t.date
            tvCategory.text = ctx.getString(R.string.bank_stmt_category_label, t.category)
            tvType.text = if (isCredit) {
                ctx.getString(R.string.bank_stmt_credited)
            } else {
                ctx.getString(R.string.bank_stmt_debited)
            }
            val chipBg = androidx.core.content.ContextCompat.getColor(
                ctx,
                if (isCredit) R.color.success else R.color.error
            )
            tvType.setBackgroundColor(chipBg)
            tvType.setTextColor(android.graphics.Color.WHITE)

            val bal = t.balance
            if (bal != null && bal > 0) {
                tvBalance.visibility = View.VISIBLE
                tvBalance.text = "Balance: ${formatRupee(bal)}"
            } else {
                tvBalance.visibility = View.GONE
            }

            if (t.narration.isNotBlank()) {
                tvNarration.visibility = View.VISIBLE
                tvNarration.text = t.narration
            } else {
                tvNarration.visibility = View.GONE
            }

            when {
                row.inExpenseBySmsRef -> {
                    tvSmsMatched.visibility = View.VISIBLE
                    tvSmsMatched.text = ctx.getString(R.string.bank_pdf_in_expenses_sms_ref)
                    btnAdd.visibility = View.GONE
                }
                row.addedToExpenses -> {
                    tvSmsMatched.visibility = View.GONE
                    btnAdd.visibility = View.VISIBLE
                    btnAdd.isEnabled = false
                    btnAdd.alpha = 0.65f
                    btnAdd.text = ctx.getString(R.string.bank_pdf_added_to_expenses)
                    btnAdd.setOnClickListener(null)
                }
                else -> {
                    if (row.matchedBankSms && t.type == StatementTransaction.TYPE_DEBIT) {
                        tvSmsMatched.visibility = View.VISIBLE
                        tvSmsMatched.text = ctx.getString(R.string.bank_pdf_matched_sms)
                    } else {
                        tvSmsMatched.visibility = View.GONE
                    }
                    btnAdd.visibility = View.VISIBLE
                    btnAdd.isEnabled = true
                    btnAdd.alpha = 1f
                    btnAdd.text = ctx.getString(R.string.bank_pdf_add_to_expenses)
                    btnAdd.setOnClickListener { onAdd(t) }
                }
            }
        }

        private fun formatRupee(amount: Double): String =
            String.format(Locale.US, "₹%,.2f", amount)
    }

    private class SmsVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card = view as MaterialCardView
        private val tvBadge = view.findViewById<TextView>(R.id.tvSmsBadge)
        private val tvMerchant = view.findViewById<TextView>(R.id.tvSmsMerchant)
        private val tvAmount = view.findViewById<TextView>(R.id.tvSmsAmount)
        private val tvDate = view.findViewById<TextView>(R.id.tvSmsDate)
        private val tvCategory = view.findViewById<TextView>(R.id.tvSmsCategory)
        private val tvHint = view.findViewById<TextView>(R.id.tvSmsHint)
        private val btn = view.findViewById<MaterialButton>(R.id.btnSmsAction)

        fun bindFirestoreUnmatched(
            txn: Transaction,
            onCardClick: (Transaction) -> Unit
        ) {
            val ctx = itemView.context
            tvBadge.text = ctx.getString(R.string.bank_sms_row_badge_not_on_pdf)
            tvHint.text = ctx.getString(R.string.bank_sms_row_hint_firestore_unmatched)
            tvHint.visibility = View.VISIBLE
            tvCategory.visibility = View.VISIBLE
            tvCategory.text = ctx.getString(
                R.string.bank_stmt_category_label,
                txn.category.ifBlank { ctx.getString(R.string.bank_sms_uncategorized) }
            )
            bindCommon(txn)
            btn.visibility = View.GONE
            card.setOnClickListener { onCardClick(txn) }
        }

        fun bindInboxPending(
            txn: Transaction,
            onCardClick: (Transaction) -> Unit,
            onAdd: (Transaction) -> Unit
        ) {
            val ctx = itemView.context
            tvBadge.text = ctx.getString(R.string.bank_sms_row_badge_not_in_expenses)
            tvHint.text = ctx.getString(R.string.bank_sms_row_hint_inbox_pending)
            tvHint.visibility = View.VISIBLE
            tvCategory.visibility = View.VISIBLE
            tvCategory.text = ctx.getString(
                R.string.bank_stmt_category_label,
                txn.category.ifBlank { ctx.getString(R.string.bank_sms_uncategorized) }
            )
            bindCommon(txn)
            btn.visibility = View.VISIBLE
            btn.text = ctx.getString(R.string.bank_sms_add_to_expenses)
            btn.setOnClickListener { onAdd(txn) }
            card.setOnClickListener { onCardClick(txn) }
        }

        private fun bindCommon(txn: Transaction) {
            tvMerchant.text = txn.merchant.ifBlank { "Transaction" }
            tvAmount.text = String.format(Locale.US, "₹%,.2f", txn.amount)
            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            tvDate.text = fmt.format(Date(txn.timestamp))
        }
    }

    companion object {
        private const val VT_HEADER = 0
        private const val VT_PDF = 1
        private const val VT_SMS_FIRESTORE = 2
        private const val VT_SMS_INBOX = 3

        private val DIFF = object : DiffUtil.ItemCallback<BankStatementRowItem>() {
            override fun areItemsTheSame(a: BankStatementRowItem, b: BankStatementRowItem): Boolean {
                if (a::class != b::class) return false
                return when {
                    a is BankStatementRowItem.SectionHeader && b is BankStatementRowItem.SectionHeader ->
                        a.title == b.title
                    a is BankStatementRowItem.FromPdf && b is BankStatementRowItem.FromPdf ->
                        a.transaction.date == b.transaction.date &&
                            a.transaction.amount == b.transaction.amount &&
                            a.transaction.merchant == b.transaction.merchant &&
                            a.transaction.type == b.transaction.type
                    a is BankStatementRowItem.UnmatchedSmsDebit && b is BankStatementRowItem.UnmatchedSmsDebit ->
                        a.transaction.id == b.transaction.id
                    a is BankStatementRowItem.SmsInboxNotInExpenses && b is BankStatementRowItem.SmsInboxNotInExpenses ->
                        a.transaction.id == b.transaction.id
                    else -> false
                }
            }

            override fun areContentsTheSame(a: BankStatementRowItem, b: BankStatementRowItem): Boolean =
                a == b
        }
    }
}
