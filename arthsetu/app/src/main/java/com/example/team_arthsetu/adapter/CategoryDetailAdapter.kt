package com.example.team_arthsetu.adapter

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.simple_idea_fin.R
import com.example.simple_idea_fin.utils.CategoryClassifier
import com.example.simple_idea_fin.utils.toRupees
import com.example.simple_idea_fin.viewmodel.CategoryStat

class CategoryDetailAdapter(
    private val onCardClick : (CategoryStat) -> Unit = {},
    private val onSetLimit  : (CategoryStat) -> Unit = {}
) : ListAdapter<CategoryStat, CategoryDetailAdapter.VH>(Diff) {

    // Limits map: category → monthly limit amount. Updated externally.
    private var limits: Map<String, Double> = emptyMap()

    fun updateLimits(newLimits: Map<String, Double>) {
        limits = newLimits
        notifyDataSetChanged()
    }

    // ── ViewHolder ──────────────────────────────────────────────────────────

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val iconCircle    : FrameLayout  = view.findViewById(R.id.iconCircle)
        val tvIcon        : TextView     = view.findViewById(R.id.tvIcon)
        val tvCategory    : TextView     = view.findViewById(R.id.tvCategory)
        val tvTxnCount    : TextView     = view.findViewById(R.id.tvTxnCount)
        val tvAmount      : TextView     = view.findViewById(R.id.tvAmount)
        val tvPercent     : TextView     = view.findViewById(R.id.tvPercent)
        val btnSetLimit   : TextView     = view.findViewById(R.id.btnSetLimit)
        val layoutLimitInfo: LinearLayout= view.findViewById(R.id.layoutLimitInfo)
        val tvLimitSpent  : TextView     = view.findViewById(R.id.tvLimitSpent)
        val tvLimitStatus : TextView     = view.findViewById(R.id.tvLimitStatus)
        val progressCategory: ProgressBar= view.findViewById(R.id.progressCategory)
        val tvPercentLabel: TextView     = view.findViewById(R.id.tvPercentLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_detail, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stat      = getItem(position)
        val catColor  = CategoryClassifier.colorFor(stat.category)
        val limit     = limits[stat.category] ?: 0.0
        val hasLimit  = limit > 0.0

        // ── Icon circle ─────────────────────────────────────────────────────
        holder.iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(catColor and 0x00FFFFFF or 0x2E000000)
        }
        holder.tvIcon.text = CategoryClassifier.iconFor(stat.category)

        // ── Text ─────────────────────────────────────────────────────────────
        holder.tvCategory.text  = stat.category
        holder.tvTxnCount.text  = "${stat.txnCount} transaction${if (stat.txnCount != 1) "s" else ""}"
        holder.tvAmount.text    = stat.amount.toRupees()

        // Percent badge
        val percentBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(if (hasLimit && stat.amount > limit) 0xFFFF3B30.toInt() else catColor)
        }
        holder.tvPercent.background = percentBg
        holder.tvPercent.text       = "${stat.percent}%"

        // ── Limit info ───────────────────────────────────────────────────────
        if (hasLimit) {
            holder.layoutLimitInfo.visibility = View.VISIBLE

            val pctOfLimit    = ((stat.amount / limit) * 100).toInt()
            val isExceeded    = stat.amount > limit
            val isNearLimit   = pctOfLimit in 80..100

            // "₹600 of ₹2,000 limit"
            holder.tvLimitSpent.text = "${stat.amount.toRupees()} of ${limit.toRupees()} limit"

            // Right status
            if (isExceeded) {
                val over = stat.amount - limit
                holder.tvLimitStatus.text      = "⚠ Over by ${over.toRupees()}"
                holder.tvLimitStatus.setTextColor(0xFFFF3B30.toInt())
            } else {
                val left = limit - stat.amount
                holder.tvLimitStatus.text      = "${left.toRupees()} left"
                holder.tvLimitStatus.setTextColor(if (isNearLimit) 0xFFFF9F0A.toInt() else 0xFF00C070.toInt())
            }

            // Progress bar color
            val barColor = when {
                isExceeded  -> 0xFFFF3B30.toInt()
                isNearLimit -> 0xFFFF9F0A.toInt()
                else        -> catColor
            }
            holder.progressCategory.progressTintList   = ColorStateList.valueOf(barColor)
            holder.progressCategory.backgroundTintList = ColorStateList.valueOf(barColor and 0x00FFFFFF or 0x1A000000)
            holder.progressCategory.max                = 100
            holder.progressCategory.progress           = pctOfLimit.coerceAtMost(100)
            holder.tvPercentLabel.text                 = "$pctOfLimit%"
        } else {
            // No limit — show % of total spend
            holder.layoutLimitInfo.visibility = View.GONE
            holder.progressCategory.progressTintList   = ColorStateList.valueOf(catColor)
            holder.progressCategory.backgroundTintList = ColorStateList.valueOf(catColor and 0x00FFFFFF or 0x1A000000)
            holder.progressCategory.max                = 100
            holder.progressCategory.progress           = stat.percent
            holder.tvPercentLabel.text                 = "${stat.percent}%"
        }

        // ── Clicks ───────────────────────────────────────────────────────────
        holder.btnSetLimit.setOnClickListener  { onSetLimit(stat) }
        holder.itemView.setOnClickListener     { onCardClick(stat) }
    }

    companion object Diff : DiffUtil.ItemCallback<CategoryStat>() {
        override fun areItemsTheSame(a: CategoryStat, b: CategoryStat) = a.category == b.category
        override fun areContentsTheSame(a: CategoryStat, b: CategoryStat) = a == b
    }
}
