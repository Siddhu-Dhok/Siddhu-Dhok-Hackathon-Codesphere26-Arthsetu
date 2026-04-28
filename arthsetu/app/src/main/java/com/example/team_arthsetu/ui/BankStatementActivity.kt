package com.example.team_arthsetu.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.bankstatement.BankStatementExpenseMapper
import com.example.team_arthsetu.bankstatement.BankStatementRowItem
import com.example.team_arthsetu.bankstatement.BankStatementRowsAdapter
import com.example.team_arthsetu.bankstatement.StatementDateUtils
import com.example.team_arthsetu.bankstatement.StatementTransaction
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.AppSnackbar
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.viewmodel.BankStatementViewModel
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BankStatementActivity : AppCompatActivity() {

    private val bankVm: BankStatementViewModel by viewModels()
    private val expenseVm: ExpenseViewModel by viewModels()

    private lateinit var layoutContent: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvLoadingStatus: TextView
    private lateinit var tvSumDebit: TextView
    private lateinit var tvSumCredit: TextView
    private lateinit var tvCount: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var etDateFrom: TextInputEditText
    private lateinit var etDateTo: TextInputEditText
    private lateinit var btnApplyDate: MaterialButton
    private lateinit var btnPickEmpty: MaterialButton
    private lateinit var btnPickFooter: MaterialButton
    private lateinit var btnSaveCloud: MaterialButton
    private lateinit var recycler: RecyclerView

    private val firestoreRepo = FirestoreRepository()

    /** Local category edits for inbox SMS rows before they are saved to expenses. */
    private val pendingInboxCategoryByTxnId = mutableMapOf<String, String>()

    /** PDF rows already saved to expenses this session (refreshes card state after add). */
    private val pdfRowsAddedToExpensesKeys = mutableSetOf<String>()

    private val adapter = BankStatementRowsAdapter(
        onAddPdfToExpenses = { st ->
            lifecycleScope.launch {
                val txn = BankStatementExpenseMapper.statementToExpense(this@BankStatementActivity, st)
                val rowKey = StatementTransaction.identityKey(st)
                expenseVm.addTransaction(txn, this@BankStatementActivity) {
                    pdfRowsAddedToExpensesKeys.add(rowKey)
                    rebuildAdapterItems()
                    AppSnackbar.show(recycler, "Added to expenses")
                }
            }
        },
        onUnmatchedSmsCardClick = { txn -> showEditCategoryForSavedSms(txn) },
        onInboxSmsCardClick = { txn -> showInboxCategoryPicker(txn, forAdd = false) },
        onAddInboxSmsToExpenses = { txn -> showInboxCategoryPicker(txn, forAdd = true) }
    )

    private var typeFilter: TypeFilter = TypeFilter.ALL
    private var dateFromMillis: Long? = null
    private var dateToMillis: Long? = null

    private enum class TypeFilter { ALL, DEBIT, CREDIT }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            pdfRowsAddedToExpensesKeys.clear()
            bankVm.processPdf(this, uri, contentResolver.getType(uri), uri.lastPathSegment)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bank_statement)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        layoutContent = findViewById(R.id.layoutContent)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus)
        tvSumDebit = findViewById(R.id.tvSumDebit)
        tvSumCredit = findViewById(R.id.tvSumCredit)
        tvCount = findViewById(R.id.tvCount)
        chipGroup = findViewById(R.id.chipGroupFilter)
        etDateFrom = findViewById(R.id.etDateFrom)
        etDateTo = findViewById(R.id.etDateTo)
        btnApplyDate = findViewById(R.id.btnApplyDateFilter)
        btnPickEmpty = findViewById(R.id.btnPickEmpty)
        btnPickFooter = findViewById(R.id.btnPickFooter)
        btnSaveCloud = findViewById(R.id.btnSaveCloud)
        recycler = findViewById(R.id.recyclerStatements)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnPickEmpty.setOnClickListener { openPicker() }
        btnPickFooter.setOnClickListener { openPicker() }

        btnSaveCloud.isEnabled = FirebaseAuth.getInstance().currentUser != null
        btnSaveCloud.setOnClickListener {
            bankVm.saveStatementToCloud(this)
        }

        btnApplyDate.setOnClickListener { applyDateInputs() }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            typeFilter = when (checkedIds.first()) {
                R.id.chipDebit -> TypeFilter.DEBIT
                R.id.chipCredit -> TypeFilter.CREDIT
                else -> TypeFilter.ALL
            }
            rebuildAdapterItems()
        }

        bankVm.loading.observe(this) { loading ->
            layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) {
                tvLoadingStatus.text = getString(R.string.bank_progress_copying)
            }
        }

        bankVm.loadingMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                tvLoadingStatus.text = msg
            }
        }

        bankVm.error.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                AppSnackbar.showLong(btnPickEmpty, msg)
                bankVm.clearError()
            }
        }

        bankVm.saveMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                AppSnackbar.show(btnSaveCloud, msg)
                bankVm.clearSaveMessage()
            }
        }

        bankVm.usedOcrFallback.observe(this) { used ->
            if (used == true) {
                AppSnackbar.showLong(
                    recycler,
                    "Little embedded text was found; OCR was used for some pages (less accurate than text-based PDFs)."
                )
                bankVm.acknowledgeOcrFallbackNotice()
            }
        }

        bankVm.fullPdfRows.observe(this) {
            refreshUiForPdfRows()
        }

        bankVm.smsMatchedFlags.observe(this) {
            if (bankVm.fullPdfRows.value?.isNotEmpty() == true) rebuildAdapterItems()
        }

        bankVm.pdfRowInExpenseBySmsRef.observe(this) {
            if (bankVm.fullPdfRows.value?.isNotEmpty() == true) rebuildAdapterItems()
        }

        bankVm.unmatchedSms.observe(this) {
            if (bankVm.fullPdfRows.value?.isNotEmpty() == true) rebuildAdapterItems()
        }

        bankVm.inboxSmsNotInExpenses.observe(this) {
            if (bankVm.fullPdfRows.value?.isNotEmpty() == true) rebuildAdapterItems()
        }

        showEmptyState()
    }

    private fun openPicker() {
        pickFile.launch("application/pdf")
    }

    private fun refreshUiForPdfRows() {
        val rows = bankVm.fullPdfRows.value.orEmpty()
        if (rows.isEmpty()) {
            showEmptyState()
            adapter.submitList(emptyList())
        } else {
            showDataState()
            rebuildAdapterItems()
        }
    }

    private fun applyDateInputs() {
        val f = etDateFrom.text?.toString()?.trim().orEmpty()
        val t = etDateTo.text?.toString()?.trim().orEmpty()
        dateFromMillis = if (f.isBlank()) null else dayStartMillis(f)
        dateToMillis = if (t.isBlank()) null else dayEndMillis(t)
        rebuildAdapterItems()
    }

    private fun dayStartMillis(dateStr: String): Long? {
        val base = StatementDateUtils.parseToMillis(dateStr) ?: return null
        val c = Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun dayEndMillis(dateStr: String): Long? {
        val base = StatementDateUtils.parseToMillis(dateStr) ?: return null
        val c = Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return c.timeInMillis
    }

    private fun passesType(t: StatementTransaction): Boolean =
        when (typeFilter) {
            TypeFilter.ALL -> true
            TypeFilter.DEBIT -> t.type == StatementTransaction.TYPE_DEBIT
            TypeFilter.CREDIT -> t.type == StatementTransaction.TYPE_CREDIT
        }

    private fun passesDate(t: StatementTransaction): Boolean {
        val from = dateFromMillis
        val to = dateToMillis
        if (from == null && to == null) return true
        val ms = StatementDateUtils.parseToMillis(t.date) ?: return true
        if (from != null && ms < from) return false
        if (to != null && ms > to) return false
        return true
    }

    private fun rebuildAdapterItems() {
        val rows = bankVm.fullPdfRows.value.orEmpty()
        if (rows.isEmpty()) return

        val flags = bankVm.smsMatchedFlags.value.orEmpty()
        val refExpenseFlags = bankVm.pdfRowInExpenseBySmsRef.value.orEmpty()
        val unmatched = bankVm.unmatchedSms.value.orEmpty()

        val items = mutableListOf<BankStatementRowItem>()
        items += BankStatementRowItem.SectionHeader("Statement (PDF)")
        rows.forEachIndexed { index, t ->
            if (passesType(t) && passesDate(t)) {
                val key = StatementTransaction.identityKey(t)
                items += BankStatementRowItem.FromPdf(
                    t,
                    flags.getOrElse(index) { false },
                    addedToExpenses = key in pdfRowsAddedToExpensesKeys,
                    inExpenseBySmsRef = refExpenseFlags.getOrElse(index) { false }
                )
            }
        }
        if (unmatched.isNotEmpty()) {
            items += BankStatementRowItem.SectionHeader("SMS debits not on this PDF")
            unmatched.forEach { items += BankStatementRowItem.UnmatchedSmsDebit(it) }
        }

        val inboxPending = bankVm.inboxSmsNotInExpenses.value.orEmpty()
        if (inboxPending.isNotEmpty()) {
            items += BankStatementRowItem.SectionHeader("Bank SMS not in expenses yet")
            inboxPending.forEach { (t, body) ->
                val cat = pendingInboxCategoryByTxnId[t.id] ?: t.category
                items += BankStatementRowItem.SmsInboxNotInExpenses(t.copy(category = cat), body)
            }
        }

        adapter.submitList(items)

        val filtered = rows.filter { passesType(it) && passesDate(it) }
        updateSummary(filtered)
    }

    private fun updateSummary(rows: List<StatementTransaction>) {
        var debit = 0.0
        var credit = 0.0
        for (t in rows) {
            when (t.type) {
                StatementTransaction.TYPE_CREDIT -> credit += t.amount
                else -> debit += t.amount
            }
        }
        tvSumDebit.text = formatRupee(debit)
        tvSumCredit.text = formatRupee(credit)
        tvCount.text = rows.size.toString()
    }

    private fun formatRupee(amount: Double): String =
        String.format(Locale.US, "₹%,.2f", amount)

    private fun showEmptyState() {
        layoutEmpty.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE
        findViewById<View>(R.id.layoutFooter).visibility = View.GONE
    }

    private fun showDataState() {
        layoutEmpty.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE
        findViewById<View>(R.id.layoutFooter).visibility = View.VISIBLE
    }

    private fun showEditCategoryForSavedSms(txn: Transaction) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            AppSnackbar.show(recycler, "Sign in to change category.")
            return
        }
        lifecycleScope.launch {
            val categories = loadUserCategoryChoices()
            val current = txn.category.ifBlank { getString(R.string.bank_sms_uncategorized) }
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
                ArrayAdapter(this@BankStatementActivity, android.R.layout.simple_list_item_1, categories)
            )
            dropdown.threshold = 0
            dropdown.setOnClickListener { dropdown.showDropDown() }
            dropdown.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) dropdown.showDropDown()
            }
            dropdown.setText(current, false)

            val dialog = MaterialAlertDialogBuilder(this@BankStatementActivity)
                .setTitle(R.string.bank_sms_edit_category_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            btnSave.setOnClickListener {
                val newText = etNew.text?.toString().orEmpty()
                val picked = dropdown.text?.toString().orEmpty()
                val normalized = normalizeCategoryInput(
                    if (newText.isNotBlank()) newText else picked
                )
                if (normalized.isBlank()) {
                    AppSnackbar.show(recycler, "Select or enter category")
                    return@setOnClickListener
                }
                expenseVm.categoriseTransaction(
                    this@BankStatementActivity,
                    txn,
                    normalized,
                    applyToAllForMerchant = cbApplyAll.isChecked
                )
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun showInboxCategoryPicker(txn: Transaction, forAdd: Boolean) {
        if (forAdd && FirebaseAuth.getInstance().currentUser == null) {
            AppSnackbar.show(recycler, "Sign in to add to expenses.")
            return
        }
        lifecycleScope.launch {
            val categories = loadUserCategoryChoices()
            val current = (pendingInboxCategoryByTxnId[txn.id] ?: txn.category)
                .ifBlank { getString(R.string.bank_sms_uncategorized) }
            val content = layoutInflater.inflate(R.layout.dialog_edit_transaction_category, null)
            val tvMerchant = content.findViewById<TextView>(R.id.tvMerchantName)
            val tvCurrent = content.findViewById<TextView>(R.id.tvCurrentCategory)
            val dropdown = content.findViewById<AutoCompleteTextView>(R.id.actvCategory)
            val etNew = content.findViewById<TextInputEditText>(R.id.etNewCategory)
            val cbApplyAll = content.findViewById<CheckBox>(R.id.cbApplyAll)
            val btnSave = content.findViewById<MaterialButton>(R.id.btnSaveCategory)
            cbApplyAll.visibility = View.GONE

            tvMerchant.text = txn.merchant.ifBlank { "Merchant" }
            tvCurrent.text = "Current: $current"
            dropdown.setAdapter(
                ArrayAdapter(this@BankStatementActivity, android.R.layout.simple_list_item_1, categories)
            )
            dropdown.threshold = 0
            dropdown.setOnClickListener { dropdown.showDropDown() }
            dropdown.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) dropdown.showDropDown()
            }
            dropdown.setText(current, false)

            val titleRes = if (forAdd) {
                R.string.bank_sms_add_category_title
            } else {
                R.string.bank_sms_inbox_category_title
            }
            val dialog = MaterialAlertDialogBuilder(this@BankStatementActivity)
                .setTitle(titleRes)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            btnSave.setOnClickListener {
                val newText = etNew.text?.toString().orEmpty()
                val picked = dropdown.text?.toString().orEmpty()
                val normalized = normalizeCategoryInput(
                    if (newText.isNotBlank()) newText else picked
                )
                if (normalized.isBlank()) {
                    AppSnackbar.show(recycler, "Select or enter category")
                    return@setOnClickListener
                }
                if (forAdd) {
                    val docId = Transaction.smsFirestoreDocumentId(txn.bankRefNo, txn.id)
                    val toSave = txn.copy(
                        category = normalized,
                        source = Constants.TXN_SOURCE_SMS,
                        id = docId
                    )
                    expenseVm.addTransactionWithPreservedId(toSave, this@BankStatementActivity) {
                        pendingInboxCategoryByTxnId.remove(txn.id)
                        bankVm.refreshSmsReconciliation(this@BankStatementActivity)
                        AppSnackbar.show(recycler, "Added to expenses")
                    }
                } else {
                    pendingInboxCategoryByTxnId[txn.id] = normalized
                    rebuildAdapterItems()
                }
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private suspend fun loadUserCategoryChoices(): List<String> = withContext(Dispatchers.IO) {
        val fromServer = runCatching { firestoreRepo.getAllMerchantCategories() }.getOrDefault(emptyList())
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
}
