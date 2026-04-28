package com.example.team_arthsetu.ui

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
import com.example.team_arthsetu.model.SimScenarioCatalog
import com.example.team_arthsetu.repository.SimulationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First screen: scenario categories (Job loss, Medical, …) with counts of cloud-saved runs.
 */
class SavedSimulationsFragment : Fragment(R.layout.fragment_saved_simulations) {

    private val repo = SimulationRepository()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvSavedCategories)
        val progress = view.findViewById<ProgressBar>(R.id.progressSavedSim)
        val empty = view.findViewById<TextView>(R.id.tvSavedSimEmpty)

        view.findViewById<View>(R.id.btnSavedSimBack).setOnClickListener {
            findNavController().navigateUp()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val grouped = withContext(Dispatchers.IO) {
                runCatching { repo.getHistory().groupBy { it.scenarioType } }.getOrDefault(emptyMap())
            }
            progress.visibility = View.GONE

            val total = grouped.values.sumOf { it.size }
            empty.visibility = if (total == 0) View.VISIBLE else View.GONE

            val builtInTypes = SimScenarioCatalog.ENTRIES.map { it.scenarioType }.toSet()
            val catalogRows = SimScenarioCatalog.ENTRIES.map { entry ->
                CategoryRowUi(
                    scenarioType = entry.scenarioType,
                    title = entry.title,
                    emoji = entry.emoji,
                    subtitle = entry.subtitle,
                    count = grouped[entry.scenarioType]?.size ?: 0
                )
            }
            val extraRows = grouped.keys
                .filter { it !in builtInTypes }
                .sortedBy { it.lowercase() }
                .map { key ->
                    CategoryRowUi(
                        scenarioType = key,
                        title = key,
                        emoji = "✨",
                        subtitle = "Custom category",
                        count = grouped[key]?.size ?: 0
                    )
                }
            val rows = catalogRows + extraRows
            rv.adapter = SavedCategoryAdapter(rows) { row ->
                val args = Bundle().apply { putString("scenarioType", row.scenarioType) }
                findNavController().navigate(
                    R.id.action_savedSimulations_to_savedSimulationDetail,
                    args
                )
            }
        }
    }
}

private data class CategoryRowUi(
    val scenarioType: String,
    val title: String,
    val emoji: String,
    val subtitle: String,
    val count: Int
)

private class SavedCategoryAdapter(
    private val items: List<CategoryRowUi>,
    private val onClick: (CategoryRowUi) -> Unit
) : RecyclerView.Adapter<SavedCategoryAdapter.VH>() {

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val emoji: TextView = root.findViewById(R.id.tvCatEmoji)
        val title: TextView = root.findViewById(R.id.tvCatTitle)
        val subtitle: TextView = root.findViewById(R.id.tvCatSubtitle)
        val count: TextView = root.findViewById(R.id.tvCatCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_sim_category, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.emoji.text = row.emoji
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.count.text = if (row.count == 0) "No saves yet" else "${row.count} saved run${if (row.count == 1) "" else "s"}"
        holder.root.setOnClickListener { onClick(row) }
    }
}
