package com.example.team_arthsetu.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.SimulationRecord
import com.example.team_arthsetu.model.SimScenarioCatalog
import com.example.team_arthsetu.repository.SimulationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Second screen: all Firestore-saved runs for one [scenarioType].
 */
class SavedSimulationDetailFragment : Fragment(R.layout.fragment_saved_simulation_detail) {

    private val repo = SimulationRepository()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scenarioType = arguments?.getString("scenarioType").orEmpty()
        val catalog = SimScenarioCatalog.ENTRIES.find { it.scenarioType == scenarioType }

        view.findViewById<TextView>(R.id.tvDetailTitle).text =
            catalog?.title ?: scenarioType.ifBlank { "Simulation" }
        view.findViewById<TextView>(R.id.tvDetailSubtitle).text =
            catalog?.subtitle ?: "Saved details"

        val rv = view.findViewById<RecyclerView>(R.id.rvSimulationRecords)
        val progress = view.findViewById<ProgressBar>(R.id.progressDetail)
        val empty = view.findViewById<TextView>(R.id.tvDetailEmpty)

        view.findViewById<View>(R.id.btnDetailBack).setOnClickListener {
            findNavController().navigateUp()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val list = withContext(Dispatchers.IO) {
                if (scenarioType.isBlank()) emptyList()
                else runCatching { repo.getSimulationsForScenario(scenarioType) }.getOrDefault(emptyList())
            }
            progress.visibility = View.GONE
            if (list.isEmpty()) {
                empty.visibility = View.VISIBLE
                rv.adapter = null
            } else {
                empty.visibility = View.GONE
                rv.adapter = SavedRecordAdapter(list)
            }
        }
    }
}

private class SavedRecordAdapter(
    private val items: List<SimulationRecord>
) : RecyclerView.Adapter<SavedRecordAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val date: TextView = root.findViewById(R.id.tvRecordDate)
        val badge: TextView = root.findViewById(R.id.tvRecordBadge)
        val metricLabel: TextView = root.findViewById(R.id.tvMetricLabel)
        val months: TextView = root.findViewById(R.id.tvRecordMonths)
        val depletion: TextView = root.findViewById(R.id.tvRecordDepletion)
        val insights: TextView = root.findViewById(R.id.tvRecordInsights)
        val inputs: TextView = root.findViewById(R.id.tvRecordInputs)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_simulation_record, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.root.context

        holder.date.text = dateFmt.format(Date(r.timestamp))

        val inv = r.scenarioType == "Investment Crash"
        val badgeText: String
        val badgeColor: Int
        when {
            r.survived && inv -> {
                badgeText = "Recovered"
                badgeColor = Color.parseColor("#16A34A")
            }
            r.survived -> {
                badgeText = "Stable"
                badgeColor = Color.parseColor("#16A34A")
            }
            inv && r.survivalMonths >= 0 -> {
                badgeText = "${r.survivalMonths} mo to recover"
                badgeColor = Color.parseColor("#D97706")
            }
            else -> {
                badgeText = "${r.survivalMonths} mo runway"
                badgeColor = Color.parseColor("#DC2626")
            }
        }
        holder.badge.text = badgeText
        val bg = GradientDrawable().apply {
            cornerRadius = 8f * ctx.resources.displayMetrics.density
            setColor(badgeColor)
        }
        holder.badge.background = bg
        holder.badge.setTextColor(Color.WHITE)

        holder.metricLabel.text = if (inv) "Recovery" else "Runway"
        holder.months.text = when {
            r.survivalMonths < 0 -> if (inv) "10yrs+" else "120+"
            else -> "${r.survivalMonths} mo"
        }

        holder.depletion.text = r.depletionDate.ifBlank { "—" }

        holder.insights.text = if (r.insights.isEmpty()) "—"
        else r.insights.joinToString("\n") { "• $it" }

        holder.inputs.text = formatInputs(r.inputs)
    }

    private fun formatInputs(m: Map<String, Any>): String {
        if (m.isEmpty()) return "—"
        return m.entries.joinToString("\n") { (k, v) ->
            val pretty = k.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            "• $pretty: $v"
        }
    }
}
