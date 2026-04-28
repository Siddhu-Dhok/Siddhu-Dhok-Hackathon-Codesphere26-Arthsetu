package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.adapter.CategoryDetailAdapter
import com.example.team_arthsetu.utils.CategoryClassifier
import com.example.team_arthsetu.utils.CategoryLimitStore
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.CategoryStat
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CategoryDetailFragment : Fragment(R.layout.fragment_category_detail) {

    private val viewModel: ExpenseViewModel by activityViewModels()

    private lateinit var tvDetailMonth        : TextView
    private lateinit var tvDetailTotalSpend   : TextView
    private lateinit var tvDetailCategoryCount: TextView
    private lateinit var tvDetailTxnCount     : TextView
    private lateinit var tvDetailAvg          : TextView
    private lateinit var progressBar          : ProgressBar
    private lateinit var layoutEmpty          : LinearLayout
    private lateinit var rvCategories         : RecyclerView

    private val MONTH_NAMES_FULL = arrayOf(
        "January","February","March","April","May","June",
        "July","August","September","October","November","December"
    )

    private val adapter = CategoryDetailAdapter(
        onCardClick = { stat -> showTransactionList(stat) },
        onSetLimit  = { stat -> showSetLimitDialog(stat) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        observeViewModel()
    }

    // ── Bind ────────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        tvDetailMonth         = v.findViewById(R.id.tvDetailMonth)
        tvDetailTotalSpend    = v.findViewById(R.id.tvDetailTotalSpend)
        tvDetailCategoryCount = v.findViewById(R.id.tvDetailCategoryCount)
        tvDetailTxnCount      = v.findViewById(R.id.tvDetailTxnCount)
        tvDetailAvg           = v.findViewById(R.id.tvDetailAvg)
        progressBar           = v.findViewById(R.id.progressBar)
        layoutEmpty           = v.findViewById(R.id.layoutEmpty)
        rvCategories          = v.findViewById(R.id.rvCategories)
    }

    private fun setupRecycler() {
        rvCategories.adapter       = adapter
        rvCategories.layoutManager = LinearLayoutManager(requireContext())
    }

    // ── Observe ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.summary.observe(viewLifecycleOwner) { summary ->
            tvDetailMonth.text      = "${MONTH_NAMES_FULL[viewModel.selectedMonth]} ${viewModel.selectedYear}"
            tvDetailTotalSpend.text = summary.monthlySpend.toRupees()

            val stats     = summary.categoryBreakdown
            val totalTxns = stats.sumOf { it.txnCount }
            val avg       = if (totalTxns > 0) summary.monthlySpend / totalTxns else 0.0

            tvDetailCategoryCount.text = stats.size.toString()
            tvDetailTxnCount.text      = totalTxns.toString()
            tvDetailAvg.text           = avg.toRupees()

            if (stats.isEmpty()) {
                layoutEmpty.visibility  = View.VISIBLE
                rvCategories.visibility = View.GONE
            } else {
                layoutEmpty.visibility  = View.GONE
                rvCategories.visibility = View.VISIBLE
                adapter.submitList(stats)
                refreshLimits()          // apply saved limits to the adapter
            }
        }
    }

    // ── Limit helpers ────────────────────────────────────────────────────────

    /** Reads all saved limits from SharedPreferences and pushes them into the adapter. */
    private fun refreshLimits() {
        val limits = CategoryLimitStore.getAllLimits(requireContext())
        adapter.updateLimits(limits)
    }

    private fun pushCategoryLimitsToServer() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                FirestoreRepository().syncCategoryLimitsFromLocal(requireContext())
            } catch (_: Exception) { }
        }
    }

    // ── Set Limit Dialog ────────────────────────────────────────────────────

    private fun showSetLimitDialog(stat: CategoryStat) {
        val ctx         = requireContext()
        val currentLimit = CategoryLimitStore.getLimit(ctx, stat.category)
        val icon        = CategoryClassifier.iconFor(stat.category)

        // Build a TextInputLayout programmatically for the dialog
        val inputLayout = layoutInflater.inflate(R.layout.dialog_set_limit, null)
        val etLimit     = inputLayout.findViewById<TextInputEditText>(R.id.etLimitAmount)
        val tvHint      = inputLayout.findViewById<TextView>(R.id.tvCurrentSpend)

        tvHint.text = "Current spend this month: ${stat.amount.toRupees()}"
        if (currentLimit > 0) etLimit.setText(currentLimit.toInt().toString())

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("$icon  Set limit for ${stat.category}")
            .setView(inputLayout)
            .setPositiveButton("Save Limit", null)  // override below to prevent auto-dismiss
            .setNeutralButton("Clear Limit") { _, _ ->
                CategoryLimitStore.clearLimit(ctx, stat.category)
                refreshLimits()
                pushCategoryLimitsToServer()
                AppSnackbar.show(requireView(), "Limit removed for ${stat.category}")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Override Save to validate before dismissing
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input  = etLimit.text?.toString()?.trim() ?: ""
            val amount = input.toDoubleOrNull()
            when {
                input.isBlank() -> {
                    etLimit.error = "Enter an amount"
                }
                amount == null || amount <= 0 -> {
                    etLimit.error = "Enter a valid amount greater than 0"
                }
                amount < stat.amount -> {
                    // Limit is already exceeded — warn but allow
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Limit already exceeded")
                        .setMessage("You've already spent ${stat.amount.toRupees()} this month on ${stat.category}. Save ₹${amount.toInt()} as your limit anyway?")
                        .setPositiveButton("Save anyway") { _, _ ->
                            CategoryLimitStore.setLimit(ctx, stat.category, amount)
                            refreshLimits()
                            pushCategoryLimitsToServer()
                            AppSnackbar.show(requireView(),
                                "Limit set: ${stat.category} → ${amount.toRupees()}/month")
                            dialog.dismiss()
                        }
                        .setNegativeButton("Change", null)
                        .show()
                }
                else -> {
                    CategoryLimitStore.setLimit(ctx, stat.category, amount)
                    refreshLimits()
                    pushCategoryLimitsToServer()
                    val left = (amount - stat.amount).toRupees()
                    AppSnackbar.showLong(requireView(),
                        "${stat.category} limit: ${amount.toRupees()}/month  ·  $left remaining")
                    dialog.dismiss()
                }
            }
        }
    }

    // ── Transaction list Dialog ─────────────────────────────────────────────

    private fun showTransactionList(stat: CategoryStat) {
        val txns = viewModel.filtered.value
            ?.filter { it.category.equals(stat.category, ignoreCase = true) }
            ?: emptyList()

        if (txns.isEmpty()) {
            AppSnackbar.show(requireView(), "No transactions for ${stat.category}")
            return
        }

        val limit    = CategoryLimitStore.getLimit(requireContext(), stat.category)
        val limitStr = if (limit > 0) "  ·  Limit ${limit.toRupees()}/month" else ""

        val lines = txns.take(15).joinToString("\n") { t ->
            val sign = if (t.type == "credit") "+" else "-"
            "• ${t.merchant.ifBlank { "Bank Transaction" }.take(20)}   $sign${t.amount.toRupees()}"
        }
        val more = if (txns.size > 15) "\n…and ${txns.size - 15} more" else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${CategoryClassifier.iconFor(stat.category)} ${stat.category}  –  ${stat.amount.toRupees()}")
            .setMessage("${stat.txnCount} transactions  ·  ${stat.percent}% of spend$limitStr\n\n$lines$more")
            .setPositiveButton("Close", null)
            .setNeutralButton("✏ Set Limit") { _, _ -> showSetLimitDialog(stat) }
            .show()
    }
}
