package com.example.team_arthsetu.bankstatement

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import java.util.Locale

class StatementTransactionAdapter :
    ListAdapter<StatementTransaction, StatementTransactionAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_statement_transaction, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMerchant = itemView.findViewById<TextView>(R.id.tvMerchant)
        private val tvAmount = itemView.findViewById<TextView>(R.id.tvAmount)
        private val tvType = itemView.findViewById<TextView>(R.id.tvType)
        private val tvDate = itemView.findViewById<TextView>(R.id.tvDate)
        private val tvCategory = itemView.findViewById<TextView>(R.id.tvCategory)
        private val tvBalance = itemView.findViewById<TextView>(R.id.tvBalance)
        private val tvNarration = itemView.findViewById<TextView>(R.id.tvNarration)
        private val tvSmsMatched = itemView.findViewById<TextView>(R.id.tvSmsMatched)
        private val btnAddToExpenses = itemView.findViewById<MaterialButton>(R.id.btnAddToExpenses)

        fun bind(t: StatementTransaction) {
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
            val chipBg = ContextCompat.getColor(
                ctx,
                if (isCredit) R.color.success else R.color.error
            )
            tvType.setBackgroundColor(chipBg)
            tvType.setTextColor(Color.WHITE)

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

            tvSmsMatched.visibility = View.GONE
            btnAddToExpenses.visibility = View.GONE
        }

        private fun formatRupee(amount: Double): String =
            String.format(Locale.US, "₹%,.2f", amount)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StatementTransaction>() {
            override fun areItemsTheSame(
                a: StatementTransaction,
                b: StatementTransaction
            ): Boolean = a.date == b.date && a.amount == b.amount && a.merchant == b.merchant && a.type == b.type

            override fun areContentsTheSame(
                a: StatementTransaction,
                b: StatementTransaction
            ): Boolean = a == b
        }
    }
}
