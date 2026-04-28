package com.example.team_arthsetu.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.simple_idea_fin.R
import com.example.simple_idea_fin.model.Transaction
import com.example.simple_idea_fin.utils.CategoryClassifier
import com.example.simple_idea_fin.utils.toSmartDate
import com.example.simple_idea_fin.utils.toRupees

class TransactionAdapter(
    private val onLongClick: (Transaction) -> Unit = {},
    private val onClick: (Transaction) -> Unit = {}
) : ListAdapter<Transaction, TransactionAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconCircle    : FrameLayout = view.findViewById(R.id.iconCircle)
        val tvIcon        : TextView    = view.findViewById(R.id.tvIcon)
        val tvMerchant    : TextView    = view.findViewById(R.id.tvMerchant)
        val tvCategoryTime: TextView    = view.findViewById(R.id.tvCategoryTime)
        val tvAmount      : TextView    = view.findViewById(R.id.tvAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val t = getItem(position)

        // Icon + colored circle
        val color = CategoryClassifier.colorFor(t.category)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color and 0x00FFFFFF or 0x20000000) // 12% alpha of category color
        }
        holder.iconCircle.background = bg
        holder.tvIcon.text = CategoryClassifier.iconFor(t.category)

        holder.tvMerchant.text     = t.merchant.ifBlank { t.category }
        holder.tvCategoryTime.text = "${t.category.uppercase()}  •  ${t.timestamp.toSmartDate()}"

        val sign  = if (t.type == "credit") "+" else "-"
        val color2 = if (t.type == "credit") 0xFF00C070.toInt() else 0xFFFF3B30.toInt()
        holder.tvAmount.text = "$sign${t.amount.toRupees()}"
        holder.tvAmount.setTextColor(color2)

        holder.itemView.setOnClickListener { onClick(t) }
        holder.itemView.setOnLongClickListener { onLongClick(t); true }
    }

    companion object Diff : DiffUtil.ItemCallback<Transaction>() {
        override fun areItemsTheSame(a: Transaction, b: Transaction) = a.id == b.id
        override fun areContentsTheSame(a: Transaction, b: Transaction) = a == b
    }
}
