package com.example.team_arthsetu.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.adapter.TransactionAdapter
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.CategoryClassifier
import com.example.team_arthsetu.utils.CategoryLimitStore
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.ExpenseLimitStore
import com.example.team_arthsetu.utils.InitialSmsSyncManager
import com.example.team_arthsetu.utils.MerchantCategoryStore
import com.example.team_arthsetu.utils.toRupees
import com.example.team_arthsetu.viewmodel.CategoryStat
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExpenseFragment : Fragment(R.layout.fragment_expense) {

    private val viewModel: ExpenseViewModel by activityViewModels()
    private val repository = FirestoreRepository()

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var tvMonthLabel   : TextView
    private lateinit var tvMonthlySpend : TextView
    private lateinit var tvMonthlyIncome: TextView
    private lateinit var tvBudgetLabel  : TextView
    private lateinit var tvBudgetPercent: TextView
    private lateinit var tvBudgetRemain : TextView
    private lateinit var progressBudget : ProgressBar
    private lateinit var layoutCategories : LinearLayout
    private lateinit var tvBreakdownEmpty : TextView
    private lateinit var chipGroup      : ChipGroup
    private lateinit var btnDaily       : TextView
    private lateinit var btnWeekly      : TextView
    private lateinit var btnMonthly     : TextView
    private lateinit var rvTransactions : RecyclerView
    private lateinit var progressBar    : ProgressBar
    private lateinit var layoutEmpty    : View
    private lateinit var tvLastN        : TextView
    private lateinit var btnViewAll     : MaterialButton

    private val adapter = TransactionAdapter(
        onLongClick = { txn -> confirmDelete(txn) },
        onClick = { txn -> showEditCategoryDialog(txn) }
    )

    private val MONTH_NAMES_SHORT = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                                             "Jul","Aug","Sep","Oct","Nov","Dec")
    private val MONTH_NAMES_FULL  = arrayOf("January","February","March","April","May","June",
                                             "July","August","September","October","November","December")

    // SMS + notification permissions (RECEIVE_SMS enables background txn notifications)
    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        InitialSmsSyncManager.performInitialSmsSync(requireContext())
        loadData()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        setupChips()
        setupTimeFilter()
        setupFab(view)
        observeViewModel()
        requestSmsPermissionAndLoad()
    }

    /**
     * Auto-refresh when user returns to this screen (e.g. after acting on a
     * notification, switching apps, or the background receiver saved new txns).
     */
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED) {
            loadData()
        }
    }

    // ── Bind views ────────────────────────────────────────────────────────────

    private fun bindViews(v: View) {
        tvMonthLabel    = v.findViewById(R.id.tvMonthLabel)
        tvMonthlySpend  = v.findViewById(R.id.tvMonthlySpend)
        tvMonthlyIncome = v.findViewById(R.id.tvMonthlyIncome)
        tvBudgetLabel   = v.findViewById(R.id.tvBudgetLabel)
        tvBudgetPercent = v.findViewById(R.id.tvBudgetPercent)
        tvBudgetRemain  = v.findViewById(R.id.tvBudgetRemaining)
        progressBudget  = v.findViewById(R.id.progressBudget)
        layoutCategories= v.findViewById(R.id.layoutCategories)
        tvBreakdownEmpty= v.findViewById(R.id.tvBreakdownEmpty)
        chipGroup       = v.findViewById(R.id.chipGroup)
        btnDaily        = v.findViewById(R.id.btnDaily)
        btnWeekly       = v.findViewById(R.id.btnWeekly)
        btnMonthly      = v.findViewById(R.id.btnMonthly)
        rvTransactions  = v.findViewById(R.id.rvTransactions)
        progressBar     = v.findViewById(R.id.progressBar)
        layoutEmpty     = v.findViewById(R.id.layoutEmpty)
        tvLastN         = v.findViewById(R.id.tvLastN)
        btnViewAll      = v.findViewById(R.id.btnViewAll)

        // Initial month label
        updateMonthLabel(viewModel.selectedMonth, viewModel.selectedYear)

        // Hamburger ☰ opens navigation drawer
        v.findViewById<android.widget.TextView>(R.id.btnExpenseHamburger)
            ?.setOnClickListener {
                (requireActivity() as? com.example.team_arthsetu.MainActivity)?.openDrawer()
            }

        // Month picker click
        tvMonthLabel.setOnClickListener { showMonthPicker() }

        // Header add button
        v.findViewById<View>(R.id.btnAddHeader).setOnClickListener { showAddSheet() }

        // View all toggle
        btnViewAll.setOnClickListener { toggleViewAll() }

        // See All → opens the Category Detail screen
        v.findViewById<android.widget.TextView>(R.id.tvSeeDetails).setOnClickListener {
            findNavController().navigate(R.id.action_expense_to_categoryDetail)
        }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecycler() {
        rvTransactions.adapter = adapter
        rvTransactions.layoutManager = LinearLayoutManager(requireContext())
        rvTransactions.isNestedScrollingEnabled = false
    }

    // ── Category chip filter ──────────────────────────────────────────────────

    private fun setupChips() {
        chipGroup.setOnCheckedStateChangeListener { group, ids ->
            val label = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.text?.toString() } ?: "All"
            viewModel.filterByCategory(label)
        }
    }

    // ── Time filter (Daily / Weekly / Monthly) ────────────────────────────────

    private fun setupTimeFilter() {
        val buttons = listOf(btnDaily to "Daily", btnWeekly to "Weekly", btnMonthly to "Monthly")
        buttons.forEach { (btn, label) ->
            btn.setOnClickListener {
                buttons.forEach { (b, _) -> setFilterBtnState(b, false) }
                setFilterBtnState(btn, true)
                viewModel.setTimeFilter(label)
            }
        }
        // Monthly is selected by default
        setFilterBtnState(btnMonthly, true)
    }

    private fun setFilterBtnState(btn: TextView, selected: Boolean) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.bg_time_filter_selected)
            btn.setTextColor(Color.WHITE)
            btn.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            btn.setBackgroundResource(R.drawable.bg_time_filter_normal)
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            btn.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    // ── FAB ───────────────────────────────────────────────────────────────────

    private fun setupFab(v: View) {
        v.findViewById<View>(R.id.fab).setOnClickListener { showAddSheet() }
    }

    // ── Permission + Load ─────────────────────────────────────────────────────

    private fun requestSmsPermissionAndLoad() {
        val ctx = requireContext()
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECEIVE_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            InitialSmsSyncManager.performInitialSmsSync(ctx)
            loadData()
        }
        else smsPermLauncher.launch(need.toTypedArray())
    }

    private fun loadData() = viewModel.loadAll(requireContext())

    // ── ViewModel observers ───────────────────────────────────────────────────

    private var showAll = false

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.filtered.observe(viewLifecycleOwner) { list ->
            val show = if (showAll) list else list.take(5)
            adapter.submitList(show)
            layoutEmpty.visibility  = if (list.isEmpty()) View.VISIBLE else View.GONE
            rvTransactions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            val label = if (showAll) "${list.size} Activities" else "Last 5 Activities"
            tvLastN.text    = label
            btnViewAll.text = if (showAll) "Show Less" else "View All Transactions"
        }

        viewModel.summary.observe(viewLifecycleOwner) { s ->
            tvMonthlySpend.text  = s.monthlySpend.toRupees()
            tvMonthlyIncome.text = s.monthlyIncome.toRupees()

            val budget = s.monthlyBudget
            val pct    = if (budget > 0) ((s.monthlySpend / budget) * 100).toInt().coerceAtMost(100) else 0
            tvBudgetLabel.text   = "Budget: ${budget.toRupees()}"
            tvBudgetPercent.text = "$pct% used"
            progressBudget.progress = pct

            val remaining = (budget - s.monthlySpend).coerceAtLeast(0.0)
            tvBudgetRemain.text = remaining.toRupees()

            buildCategoryBreakdown(s.categoryBreakdown)
        }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg ?: return@observe
            AppSnackbar.showLong(requireView(), msg)
            viewModel.clearError()
        }

        // Show category picker for uncategorised SMS transactions
        viewModel.uncategorised.observe(viewLifecycleOwner) { pending ->
            val first = pending.firstOrNull() ?: return@observe
            showCategoryPicker(first)
        }
    }

    // ── Month picker ──────────────────────────────────────────────────────────

    private fun showMonthPicker() {
        val now  = Calendar.getInstance()
        val curY = now.get(Calendar.YEAR)

        // Build list: 3 years back × 12 months = 36 entries
        val items = mutableListOf<Pair<Int, Int>>() // Pair(month, year)
        for (year in curY downTo curY - 2) {
            for (month in 11 downTo 0) {
                items.add(month to year)
            }
        }

        val labels = items.map { (m, y) -> "${MONTH_NAMES_FULL[m]} $y" }.toTypedArray()
        val current = items.indexOfFirst {
            it.first == viewModel.selectedMonth && it.second == viewModel.selectedYear
        }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Month")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val (m, y) = items[which]
                viewModel.setMonthYear(m, y)
                updateMonthLabel(m, y)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMonthLabel(month: Int, year: Int) {
        tvMonthLabel.text = "${MONTH_NAMES_FULL[month]} $year ▾"
    }

    // ── Category Breakdown ────────────────────────────────────────────────────

    private fun buildCategoryBreakdown(stats: List<CategoryStat>) {
        layoutCategories.removeAllViews()

        if (stats.isEmpty()) {
            tvBreakdownEmpty.visibility = View.VISIBLE
            return
        }
        tvBreakdownEmpty.visibility = View.GONE

        val limits = CategoryLimitStore.getAllLimits(requireContext())

        stats.take(5).forEach { stat ->
            val limit      = limits[stat.category] ?: 0.0
            val hasLimit   = limit > 0.0
            val isExceeded = hasLimit && stat.amount > limit
            val isNear     = hasLimit && stat.amount >= limit * 0.8 && !isExceeded

            val barColor = when {
                isExceeded -> 0xFFFF3B30.toInt()
                isNear     -> 0xFFFF9F0A.toInt()
                else       -> CategoryClassifier.colorFor(stat.category)
            }
            val catColor = CategoryClassifier.colorFor(stat.category)

            val rowView  = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dpToPx(12))
            }

            // Name + amount row
            val nameRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Colored dot indicator (red when exceeded)
            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(barColor)
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).also {
                    it.marginEnd = dpToPx(8)
                }
            }

            // Category name (+ ⚠ if limit exceeded)
            val nameLabel = if (isExceeded) "${stat.category} ⚠" else stat.category
            val tvName = TextView(requireContext()).apply {
                text = nameLabel
                textSize = 13f
                setTextColor(
                    if (isExceeded) 0xFFFF3B30.toInt()
                    else ContextCompat.getColor(requireContext(), R.color.text_primary)
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Amount — show "₹600 / ₹2,000" if limit set, else normal
            val amtLabel = if (hasLimit)
                "${stat.amount.toRupees()} / ${limit.toRupees()}"
            else
                "${stat.amount.toRupees()}  (${stat.percent}%)"

            val tvAmt = TextView(requireContext()).apply {
                text = amtLabel
                textSize = 12f
                setTextColor(
                    if (isExceeded) 0xFFFF3B30.toInt()
                    else ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
            }

            nameRow.addView(dot)
            nameRow.addView(tvName)
            nameRow.addView(tvAmt)

            // Progress bar — limit-based if limit set, else % of total
            val barProgress = if (hasLimit)
                ((stat.amount / limit) * 100).toInt().coerceAtMost(100)
            else stat.percent

            val bar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max      = 100
                progress = barProgress
                progressTintList   = android.content.res.ColorStateList.valueOf(barColor)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E8E8F0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(7)
                ).also { it.topMargin = dpToPx(5) }
            }

            rowView.addView(nameRow)
            rowView.addView(bar)
            layoutCategories.addView(rowView)
        }
    }

    // ── View All ──────────────────────────────────────────────────────────────

    private fun toggleViewAll() {
        showAll = !showAll
        val all = viewModel.filtered.value ?: emptyList()
        adapter.submitList(if (showAll) all else all.take(5))
        tvLastN.text    = if (showAll) "${all.size} Activities" else "Last 5 Activities"
        btnViewAll.text = if (showAll) "Show Less" else "View All Transactions"
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun confirmDelete(txn: Transaction) {
        if (txn.id.startsWith("sms_")) {
            AppSnackbar.show(requireView(), "SMS-parsed transactions cannot be deleted here.")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Transaction")
            .setMessage("Remove \"${txn.merchant}\" (${txn.amount.toRupees()})?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTransaction(txn.id, requireContext()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Edit category (tap on transaction) ────────────────────────────────────

    private fun showEditCategoryDialog(txn: Transaction) {
        lifecycleScope.launch {
            val categories = loadUserCategoryChoices()
            val ctx = requireContext()
            val current = txn.category.ifBlank { "Uncategorized" }
            val content = layoutInflater.inflate(R.layout.dialog_edit_transaction_category, null)
            val tvMerchant = content.findViewById<TextView>(R.id.tvMerchantName)
            val tvCurrent = content.findViewById<TextView>(R.id.tvCurrentCategory)
            val dropdown = content.findViewById<AutoCompleteTextView>(R.id.actvCategory)
            val etNew = content.findViewById<TextInputEditText>(R.id.etNewCategory)
            val cbApplyAll = content.findViewById<CheckBox>(R.id.cbApplyAll)
            val btnSave = content.findViewById<MaterialButton>(R.id.btnSaveCategory)

            tvMerchant.text = txn.merchant.ifBlank { "Merchant" }
            tvCurrent.text = "Current: $current"
            dropdown.setAdapter(
                ArrayAdapter(ctx, android.R.layout.simple_list_item_1, categories)
            )
            dropdown.threshold = 0
            dropdown.setOnClickListener { dropdown.showDropDown() }
            dropdown.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) dropdown.showDropDown()
            }
            dropdown.setText(current, false)

            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Edit Category")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .create()

            btnSave.setOnClickListener {
                val newText = etNew.text?.toString().orEmpty()
                val picked = dropdown.text?.toString().orEmpty()
                val normalized = normalizeCategoryInput(
                    if (newText.isNotBlank()) newText else picked
                )
                if (normalized.isBlank()) {
                    AppSnackbar.show(requireView(), "Select or enter category")
                    return@setOnClickListener
                }
                viewModel.categoriseTransaction(
                    ctx,
                    txn,
                    normalized,
                    applyToAllForMerchant = cbApplyAll.isChecked
                )
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    // ── Category picker for uncategorised SMS transactions ─────────────────────

    private var categoryPickerShowing = false

    /**
     * Shows a BottomSheet asking the user to pick or type a category for an uncategorised transaction.
     * After the user picks, the mapping is saved to MerchantCategoryStore so future transactions
     * from the same merchant auto-categorise.
     */
    private fun showCategoryPicker(txn: Transaction) {
        if (categoryPickerShowing) return
        categoryPickerShowing = true

        val dialog = BottomSheetDialog(requireContext()).apply {
            // Fixes keyboard overlap: sheet resizes rather than overlapping
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val ctx = requireContext()

        // ── ScrollView wrapper (prevents keyboard overlap) ────────────────
        val scrollView = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(40))
        }

        // ── Handle bar ────────────────────────────────────────────────────
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(4)).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dpToPx(20)
            }
            background = ContextCompat.getDrawable(ctx, R.drawable.sheet_handle_bg)
        }
        root.addView(handle)

        // ── Title ─────────────────────────────────────────────────────────
        val tvTitle = TextView(ctx).apply {
            text = "Categorise Transaction"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, 0, 0, dpToPx(16))
        }
        root.addView(tvTitle)

        // ── Transaction info card ─────────────────────────────────────────
        val amountLabel = if (txn.type == "debit") "💸 Spent" else "💰 Received"
        val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        val infoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(20) }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F8"))
                cornerRadius = dpToPx(14).toFloat()
            }
            background = bg
        }

        val tvMerchant = TextView(ctx).apply {
            text = txn.merchant
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, 0, 0, dpToPx(4))
        }
        infoCard.addView(tvMerchant)

        val tvDetails = TextView(ctx).apply {
            text = "$amountLabel: ${txn.amount.toRupees()}  •  ${dateFmt.format(java.util.Date(txn.timestamp))}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        infoCard.addView(tvDetails)
        root.addView(infoCard)

        // ── Category dropdown ─────────────────────────────────────────────
        val dropdownLayout = com.google.android.material.textfield.TextInputLayout(
            ctx, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(12) }
            hint = "Choose a category"
        }
        val spinner = AutoCompleteTextView(ctx).apply {
            setAdapter(
                ArrayAdapter(
                    ctx,
                    android.R.layout.simple_list_item_1,
                    Constants.CATEGORIES.distinct()
                )
            )
            focusable = View.NOT_FOCUSABLE
            inputType = android.text.InputType.TYPE_NULL
            textSize = 16f
        }
        dropdownLayout.addView(spinner)
        root.addView(dropdownLayout)

        // ── OR divider ────────────────────────────────────────────────────
        val dividerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(12) }
        }
        val divLine = { View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(1), 1f)
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }}
        val tvOr = TextView(ctx).apply {
            text = "  OR  "
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
        }
        dividerRow.addView(divLine())
        dividerRow.addView(tvOr)
        dividerRow.addView(divLine())
        root.addView(dividerRow)

        // ── Custom category EditText ──────────────────────────────────────
        val customLayout = com.google.android.material.textfield.TextInputLayout(
            ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpToPx(24) }
            hint = "Type custom category name"
        }
        val etCustom = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            textSize = 16f
        }
        customLayout.addView(etCustom)
        root.addView(customLayout)

        // ── Save button ───────────────────────────────────────────────────
        val btnSave = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "Save Category"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)
            )
            cornerRadius = dpToPx(14)
        }
        root.addView(btnSave)

        // ── Skip button ───────────────────────────────────────────────────
        val btnSkip = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Skip"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(8) }
            cornerRadius = dpToPx(14)
        }
        root.addView(btnSkip)

        // ── Wire up ───────────────────────────────────────────────────────
        btnSave.setOnClickListener {
            val custom   = etCustom.text?.toString()?.trim() ?: ""
            val dropdown = spinner.text?.toString()?.trim() ?: ""
            val chosen   = custom.ifEmpty { dropdown }

            if (chosen.isBlank()) {
                AppSnackbar.show(requireView(), "Please select or type a category")
                return@setOnClickListener
            }

            viewModel.categoriseTransaction(requireContext(), txn, chosen)
            categoryPickerShowing = false
            dialog.dismiss()
        }

        btnSkip.setOnClickListener {
            viewModel.categoriseTransaction(requireContext(), txn, "Other")
            categoryPickerShowing = false
            dialog.dismiss()
        }

        dialog.setOnCancelListener { categoryPickerShowing = false }

        scrollView.addView(root)
        dialog.setContentView(scrollView)

        // Expand fully so content isn't half-hidden
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        dialog.show()
    }

    // ── Add Transaction sheet ─────────────────────────────────────────────────

    private fun showAddSheet() {
        ManualEntryBottomSheetFragment()
            .show(childFragmentManager, ManualEntryBottomSheetFragment.TAG)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private suspend fun loadUserCategoryChoices(): List<String> = withContext(Dispatchers.IO) {
        val fromServer = runCatching { repository.getAllMerchantCategories() }.getOrDefault(emptyList())
        val merged = (fromServer + Constants.CATEGORIES)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
        if (merged.isNotEmpty()) merged else Constants.CATEGORIES
    }

    private fun normalizeCategoryInput(raw: String): String {
        val compact = raw.trim().replace(Regex("\\s+"), " ")
        if (compact.isBlank()) return ""
        return compact.split(' ')
            .joinToString(" ") { token ->
                token.lowercase(Locale.getDefault()).replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
                }
            }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
