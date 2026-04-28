package com.example.team_arthsetu.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.adapter.AssetAdapter
import com.example.team_arthsetu.adapter.LiabilityAdapter
import com.example.team_arthsetu.model.Asset
import com.example.team_arthsetu.model.Liability
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.AllocationSlice
import com.example.team_arthsetu.viewmodel.WealthViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.team_arthsetu.utils.AppSnackbar

class WealthFragment : Fragment(R.layout.fragment_wealth) {

    private val viewModel: WealthViewModel by activityViewModels()

    private lateinit var tvNetWorth       : TextView
    private lateinit var tvYoyBadge       : TextView
    private lateinit var tvBankBalance    : TextView
    private lateinit var tvInvested       : TextView
    private lateinit var tvBorrowing      : TextView
    private lateinit var donutChart       : DonutChartView
    private lateinit var layoutLegend     : LinearLayout
    private lateinit var tvAllocationEmpty: TextView
    private lateinit var rvAssets         : RecyclerView
    private lateinit var tvAssetsEmpty    : TextView
    private lateinit var rvLiabilities    : RecyclerView
    private lateinit var tvLiabilitiesEmpty: TextView
    private lateinit var progressBar      : ProgressBar
    private lateinit var btnAddAsset      : MaterialButton
    private lateinit var btnAddLiability  : MaterialButton
    private lateinit var tvViewAllAssets  : TextView
    private lateinit var tvViewAllLiab    : TextView
    private lateinit var btnStatements    : MaterialButton

    private val assetAdapter     = AssetAdapter     { asset     -> confirmDeleteAsset(asset) }
    private val liabilityAdapter = LiabilityAdapter { liability -> confirmDeleteLiability(liability) }

    private var showAllAssets      = false
    private var showAllLiabilities = false

    // Reload wealth data when returning from Add screens
    private val addAssetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.loadAll()
    }

    private val addLiabilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.loadAll()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclers()
        setupClicks()
        observeViewModel()
        viewModel.loadAll()
    }

    // ── Bind ─────────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        tvNetWorth        = v.findViewById(R.id.tvNetWorth)
        tvYoyBadge        = v.findViewById(R.id.tvYoyBadge)
        tvBankBalance     = v.findViewById(R.id.tvBankBalance)
        tvInvested        = v.findViewById(R.id.tvInvested)
        tvBorrowing       = v.findViewById(R.id.tvBorrowing)
        donutChart        = v.findViewById(R.id.donutChart)
        layoutLegend      = v.findViewById(R.id.layoutLegend)
        tvAllocationEmpty = v.findViewById(R.id.tvAllocationEmpty)
        rvAssets          = v.findViewById(R.id.rvAssets)
        tvAssetsEmpty     = v.findViewById(R.id.tvAssetsEmpty)
        rvLiabilities     = v.findViewById(R.id.rvLiabilities)
        tvLiabilitiesEmpty= v.findViewById(R.id.tvLiabilitiesEmpty)
        progressBar       = v.findViewById(R.id.progressBar)
        btnAddAsset       = v.findViewById(R.id.btnAddAsset)
        btnAddLiability   = v.findViewById(R.id.btnAddLiability)
        tvViewAllAssets   = v.findViewById(R.id.tvViewAllAssets)
        tvViewAllLiab     = v.findViewById(R.id.tvViewAllLiabilities)
        btnStatements     = v.findViewById(R.id.btnStatements)
    }

    private fun setupRecyclers() {
        rvAssets.adapter       = assetAdapter
        rvAssets.layoutManager = LinearLayoutManager(requireContext())
        rvAssets.isNestedScrollingEnabled = false

        rvLiabilities.adapter       = liabilityAdapter
        rvLiabilities.layoutManager = LinearLayoutManager(requireContext())
        rvLiabilities.isNestedScrollingEnabled = false
    }

    private fun setupClicks() {
        btnAddAsset.setOnClickListener     { showAddAssetSheet() }
        btnAddLiability.setOnClickListener { showAddLiabilitySheet() }
        tvViewAllAssets.setOnClickListener  { toggleAllAssets() }
        tvViewAllLiab.setOnClickListener    { toggleAllLiabilities() }
        btnStatements.setOnClickListener    {
            AppSnackbar.show(requireView(), "Detailed statements coming soon!")
        }
        // Hamburger ☰ opens the navigation drawer
        requireView().findViewById<android.widget.TextView>(R.id.btnHamburger)
            ?.setOnClickListener {
                (requireActivity() as? com.example.team_arthsetu.MainActivity)?.openDrawer()
            }
    }

    // ── Observe ──────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.summary.observe(viewLifecycleOwner) { s ->
            // Net worth
            tvNetWorth.text = formatCrL(s.netWorth)

            val yoySign  = if (s.avgChangePercent >= 0) "+" else ""
            tvYoyBadge.text = "$yoySign${String.format("%.1f", s.avgChangePercent)}% YOY"
            tvYoyBadge.setTextColor(if (s.avgChangePercent >= 0) 0xFF00C070.toInt() else 0xFFFF3B30.toInt())

            // Quick stats
            tvBankBalance.text = formatCrL(s.bankBalance)
            tvInvested.text    = formatCrL(s.invested)
            tvBorrowing.text   = formatCrL(s.borrowing)

            // Donut chart
            buildAllocationChart(s.allocation)
        }

        viewModel.assets.observe(viewLifecycleOwner) { assets ->
            val show = if (showAllAssets) assets else assets.take(4)
            assetAdapter.submitList(show)
            tvAssetsEmpty.visibility = if (assets.isEmpty()) View.VISIBLE else View.GONE
            rvAssets.visibility      = if (assets.isEmpty()) View.GONE    else View.VISIBLE
            tvViewAllAssets.text     = if (showAllAssets || assets.size <= 4) "View All ›" else "View All ›"
        }

        viewModel.liabilities.observe(viewLifecycleOwner) { liabs ->
            val show = if (showAllLiabilities) liabs else liabs.take(3)
            liabilityAdapter.submitList(show)
            tvLiabilitiesEmpty.visibility = if (liabs.isEmpty()) View.VISIBLE else View.GONE
            rvLiabilities.visibility      = if (liabs.isEmpty()) View.GONE    else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg ?: return@observe
            AppSnackbar.showLong(requireView(), msg)
            viewModel.clearError()
        }
    }

    // ── Donut chart + legend ─────────────────────────────────────────────────

    private fun buildAllocationChart(slices: List<AllocationSlice>) {
        if (slices.isEmpty()) {
            donutChart.visibility      = View.GONE
            layoutLegend.visibility    = View.GONE
            tvAllocationEmpty.visibility = View.VISIBLE
            return
        }
        donutChart.visibility        = View.VISIBLE
        layoutLegend.visibility      = View.VISIBLE
        tvAllocationEmpty.visibility = View.GONE

        // Set chart slices
        donutChart.setSlices(slices.map {
            DonutChartView.Slice(it.label, it.percent, it.color)
        })

        // Build legend
        layoutLegend.removeAllViews()
        slices.forEach { slice ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }
            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(slice.color)
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).also {
                    it.marginEnd = dpToPx(7)
                }
            }
            val tvLabel = TextView(requireContext()).apply {
                text = "${slice.label} ${slice.percent.toInt()}%"
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_secondary, null))
            }
            row.addView(dot)
            row.addView(tvLabel)
            layoutLegend.addView(row)
        }
    }

    // ── Toggle view all ──────────────────────────────────────────────────────

    private fun toggleAllAssets() {
        showAllAssets = !showAllAssets
        val all = viewModel.assets.value ?: emptyList()
        assetAdapter.submitList(if (showAllAssets) all else all.take(4))
        tvViewAllAssets.text = if (showAllAssets) "Show Less" else "View All ›"
    }

    private fun toggleAllLiabilities() {
        showAllLiabilities = !showAllLiabilities
        val all = viewModel.liabilities.value ?: emptyList()
        liabilityAdapter.submitList(if (showAllLiabilities) all else all.take(3))
        tvViewAllLiab.text = if (showAllLiabilities) "Show Less" else "View All ›"
    }

    // ── Open dedicated Add screens ────────────────────────────────────────────

    private fun showAddAssetSheet() {
        addAssetLauncher.launch(Intent(requireContext(), AddAssetActivity::class.java))
    }

    private fun showAddLiabilitySheet() {
        addLiabilityLauncher.launch(Intent(requireContext(), AddLiabilityActivity::class.java))
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun confirmDeleteAsset(asset: Asset) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove Asset")
            .setMessage("Remove \"${asset.name}\" (${formatCrL(asset.value)})? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteAsset(asset.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteLiability(liability: Liability) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove Liability")
            .setMessage("Remove \"${liability.name}\"? This cannot be undone.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteLiability(liability.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    private fun formatCrL(v: Double): String = when {
        v >= 1_00_00_000 -> "₹${String.format("%.2f", v / 1_00_00_000)}Cr"
        v >= 1_00_000    -> "₹${String.format("%.1f", v / 1_00_000)}L"
        v >= 1_000       -> "₹${String.format("%.1f", v / 1_000)}K"
        else             -> v.toRupees()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
