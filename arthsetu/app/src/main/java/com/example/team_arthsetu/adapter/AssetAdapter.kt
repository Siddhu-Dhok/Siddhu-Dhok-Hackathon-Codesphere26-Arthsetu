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
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Asset
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.WealthViewModel

class AssetAdapter(
    private val onLongClick: (Asset) -> Unit = {}
) : ListAdapter<Asset, AssetAdapter.VH>(Diff) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iconCircle : FrameLayout = v.findViewById(R.id.iconCircle)
        val tvIcon     : TextView    = v.findViewById(R.id.tvIcon)
        val tvName     : TextView    = v.findViewById(R.id.tvName)
        val tvNote     : TextView    = v.findViewById(R.id.tvNote)
        val tvValue    : TextView    = v.findViewById(R.id.tvValue)
        val tvChange   : TextView    = v.findViewById(R.id.tvChange)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_asset, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val asset = getItem(position)
        val color = WealthViewModel.assetColor(asset.type)

        // Icon circle
        holder.iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color and 0x00FFFFFF or 0x22000000)
        }
        holder.tvIcon.text  = WealthViewModel.assetIcon(asset.type)
        holder.tvName.text  = asset.name.ifBlank { "Asset" }

        // Note row: institution + SIP badge if applicable
        val sip = if (asset.monthlyInvestment > 0)
            "  SIP ACTIVE + ${formatL(asset.monthlyInvestment)}/mo" else ""
        holder.tvNote.text = "${asset.note}$sip".trim().ifBlank { asset.type.replace("_", " ").uppercase() }

        holder.tvValue.text = asset.value.toRupees()

        // Change %
        if (asset.changePercent != 0.0) {
            val sign  = if (asset.changePercent > 0) "+" else ""
            val color2 = if (asset.changePercent >= 0) 0xFF00C070.toInt() else 0xFFFF3B30.toInt()
            holder.tvChange.visibility = View.VISIBLE
            holder.tvChange.text       = "$sign${String.format("%.1f", asset.changePercent)}%"
            holder.tvChange.setTextColor(color2)
        } else {
            holder.tvChange.visibility = View.GONE
        }

        holder.itemView.setOnLongClickListener { onLongClick(asset); true }
    }

    private fun formatL(v: Double): String = when {
        v >= 1_00_00_000 -> "₹${String.format("%.2f", v / 1_00_00_000)}Cr"
        v >= 1_00_000    -> "₹${String.format("%.1f", v / 1_00_000)}L"
        else             -> "₹${v.toInt()}K"
    }

    companion object Diff : DiffUtil.ItemCallback<Asset>() {
        override fun areItemsTheSame(a: Asset, b: Asset) = a.id == b.id
        override fun areContentsTheSame(a: Asset, b: Asset) = a == b
    }
}
