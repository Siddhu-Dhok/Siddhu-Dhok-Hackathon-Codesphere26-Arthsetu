package com.example.team_arthsetu.adapter

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
import com.example.simple_idea_fin.model.Liability
import com.example.simple_idea_fin.utils.toRupees
import com.example.simple_idea_fin.viewmodel.WealthViewModel

class LiabilityAdapter(
    private val onLongClick: (Liability) -> Unit = {}
) : ListAdapter<Liability, LiabilityAdapter.VH>(Diff) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iconCircle : FrameLayout = v.findViewById(R.id.iconCircle)
        val tvIcon     : TextView    = v.findViewById(R.id.tvIcon)
        val tvName     : TextView    = v.findViewById(R.id.tvName)
        val tvNote     : TextView    = v.findViewById(R.id.tvNote)
        val tvAmount   : TextView    = v.findViewById(R.id.tvValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_liability, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = getItem(position)

        // Icon (orange tint for liabilities)
        val iconColor = 0xFFFF9F0A.toInt()
        holder.iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(iconColor and 0x00FFFFFF or 0x22000000)
        }
        holder.tvIcon.text  = WealthViewModel.liabilityIcon(l.type)
        holder.tvName.text  = l.name.ifBlank { l.type.replace("_", " ").uppercase() }

        val ratePart = if (l.interestRate > 0) " @${String.format("%.1f", l.interestRate)}% p.a." else ""
        val emiPart  = if (l.nextEmiDate.isNotBlank()) "  ·  EMI: ${l.nextEmiDate}" else ""
        val subtitle = "${l.bank}$ratePart$emiPart".trim()
        holder.tvNote.text = subtitle.ifBlank { l.type.replace("_", " ").uppercase() }

        holder.tvAmount.text = "-${l.amount.toRupees()}"
        holder.tvAmount.setTextColor(0xFFFF3B30.toInt())

        holder.itemView.setOnLongClickListener { onLongClick(l); true }
    }

    companion object Diff : DiffUtil.ItemCallback<Liability>() {
        override fun areItemsTheSame(a: Liability, b: Liability) = a.id == b.id
        override fun areContentsTheSame(a: Liability, b: Liability) = a == b
    }
}
