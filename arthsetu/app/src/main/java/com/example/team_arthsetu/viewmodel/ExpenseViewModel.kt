package com.example.team_arthsetu.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.CategoryClassifier
import com.example.team_arthsetu.utils.CategoryRequiredNotifier
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.ExpenseLimitStore
import com.example.team_arthsetu.utils.MerchantCategoryStore
import com.example.team_arthsetu.utils.MerchantNormalizer
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

// ── Data models ────────────────────────────────────────────────────────────────

data class ExpenseSummary(
    val monthlySpend      : Double          = 0.0,
    val monthlyIncome     : Double          = 0.0,
    val monthlyBudget     : Double          = 5000.0,
    val categoryBreakdown : List<CategoryStat> = emptyList()
)

data class CategoryStat(
    val category: String,
    val amount  : Double,
    val percent : Int,
    val txnCount: Int = 0
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class ExpenseViewModel : ViewModel() {

    private val repository = FirestoreRepository()
    private var monthlyBudgetLimit: Double = 0.0
    private var transactionsListener: ListenerRegistration? = null

    private val _allTransactions = MutableLiveData<List<Transaction>>(emptyList())
    val allTransactions: LiveData<List<Transaction>> = _allTransactions

    private val _filtered = MutableLiveData<List<Transaction>>(emptyList())
    val filtered: LiveData<List<Transaction>> = _filtered

    private val _summary = MutableLiveData<ExpenseSummary>()
    val summary: LiveData<ExpenseSummary> = _summary

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /** Debit transactions that still need a category (never credits). */
    private val _uncategorised = MutableLiveData<List<Transaction>>(emptyList())
    val uncategorised: LiveData<List<Transaction>> = _uncategorised

    /** Credit / income rows for separate UI (no categorisation queue). */
    private val _incomeTransactions = MutableLiveData<List<Transaction>>(emptyList())
    val incomeTransactions: LiveData<List<Transaction>> = _incomeTransactions

    // ── Filter state ──────────────────────────────────────────────────────────

    var selectedCategory: String = "All"
    var activeTimeFilter: String = "Monthly"   // "Daily" | "Weekly" | "Monthly"
    var selectedMonth   : Int    = Calendar.getInstance().get(Calendar.MONTH)
    var selectedYear    : Int    = Calendar.getInstance().get(Calendar.YEAR)

    // ── Load ──────────────────────────────────────────────────────────────────

    fun loadAll(context: Context) {
        val appCtx = context.applicationContext
        monthlyBudgetLimit = try {
            ExpenseLimitStore.getMonthlyLimit(appCtx).coerceAtLeast(0.0)
        } catch (_: Exception) {
            0.0
        }
        _isLoading.value = true
        transactionsListener?.remove()
        transactionsListener = repository.listenToTransactions { cloud, err ->
            if (err != null) {
                Log.e(TAG, "transactions snapshot listener failed", err)
                _error.postValue(err.message)
                _isLoading.postValue(false)
                return@listenToTransactions
            }
            viewModelScope.launch(Dispatchers.Main.immediate) {
                try {
                    applyLoadedTransactions(appCtx, cloud)
                } catch (e: Exception) {
                    Log.e(TAG, "applyLoadedTransactions failed", e)
                    _error.value = e.message
                } finally {
                    _isLoading.value = false
                }
            }
        }
        if (transactionsListener == null) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                applyLoadedTransactions(appCtx, emptyList())
                _isLoading.value = false
            }
        }
    }

    override fun onCleared() {
        transactionsListener?.remove()
        transactionsListener = null
        super.onCleared()
    }

    private fun applyLoadedTransactions(context: Context, cloud: List<Transaction>) {
        val pendingCategorisation = cloud.filter { tx ->
            tx.type.equals(Constants.DEBIT, ignoreCase = true) &&
                CategoryClassifier.needsDebitUserCategorization(tx.merchant, tx.category)
        }
        _uncategorised.value = pendingCategorisation
        _incomeTransactions.value =
            cloud.filter { it.type.equals(Constants.CREDIT, ignoreCase = true) }
                .sortedByDescending { it.timestamp }

        if (pendingCategorisation.isNotEmpty()) {
            val appCtx = context.applicationContext
            pendingCategorisation
                .groupBy {
                    val k = MerchantNormalizer.looseKey(it.merchant)
                    if (k.isNotBlank()) k else it.merchant.trim().uppercase()
                }
                .values.forEach { group ->
                    val txn = group.minByOrNull { it.timestamp } ?: return@forEach
                    CategoryRequiredNotifier.trigger(appCtx, txn)
                }
        }

        val merged = cloud.sortedByDescending { it.timestamp }
        cachedTransactions = merged
        _allTransactions.value = merged
        rebuild(merged)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.syncAiFinancialMetrics(merged)
            } catch (_: Exception) {
            }
        }
    }

    // ── Category update (called after user picks a category) ──────────────────

    /**
     * Updates a transaction's category in Firestore and saves the merchant→category
     * mapping so future transactions from this merchant auto-categorise.
     */
    fun categoriseTransaction(
        context: Context,
        txn: Transaction,
        category: String,
        applyToAllForMerchant: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val merchantNorm = txn.merchant.trim().uppercase()
                val merchantKey = MerchantNormalizer.looseKey(txn.merchant)

                repository.updateSingleTransaction(txn.id, category)

                if (txn.type.equals(Constants.CREDIT, ignoreCase = true)) {
                    val updated = _allTransactions.value?.map {
                        if (it.id == txn.id) it.copy(category = category) else it
                    } ?: emptyList()
                    cachedTransactions = updated
                    _allTransactions.value = updated
                    rebuild(updated)
                    return@launch
                }

                MerchantCategoryStore.setCategory(context, txn.merchant, category)
                try {
                    repository.saveMerchantMapping(txn.merchant, category)
                } catch (_: Exception) { }
                if (applyToAllForMerchant) {
                    repository.updateCategoryForAllTransactions(merchantNorm, category)
                }

                val current = _uncategorised.value?.toMutableList() ?: mutableListOf()
                current.removeAll { it.id == txn.id }
                _uncategorised.value = current

                val updated = _allTransactions.value?.map {
                    val sameMerchant = it.type.equals(Constants.DEBIT, ignoreCase = true) &&
                        (it.merchant.trim().uppercase() == merchantNorm ||
                            (merchantKey.isNotBlank() && it.merchantKey == merchantKey))
                    when {
                        it.id == txn.id -> it.copy(category = category)
                        applyToAllForMerchant && sameMerchant -> it.copy(category = category)
                        else -> it
                    }
                } ?: emptyList()
                cachedTransactions = updated
                _allTransactions.value = updated
                rebuild(updated)
            } catch (e: Exception) {
                _error.value = "Failed to update category: ${e.message}"
            }
        }
    }

    // ── Filter setters (trigger UI update without re-fetching) ────────────────

    fun setTimeFilter(filter: String) {
        activeTimeFilter = filter
        rebuild(_allTransactions.value ?: return)
    }

    fun filterByCategory(category: String) {
        selectedCategory = category
        rebuild(_allTransactions.value ?: return)
    }

    /** Called when user picks a different month in the month picker */
    fun setMonthYear(month: Int, year: Int) {
        selectedMonth = month
        selectedYear  = year
        rebuild(_allTransactions.value ?: return)
    }

    // ── Add / Delete ──────────────────────────────────────────────────────────

    fun addTransaction(txn: Transaction, context: Context, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.addTransaction(txn)
                loadAll(context)
                onDone()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** Writes at [Transaction.id] (e.g. `sms_<inboxId>`) so inbox and Firestore stay aligned. */
    fun addTransactionWithPreservedId(txn: Transaction, context: Context, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.addTransactionWithId(txn)
                loadAll(context)
                onDone()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun deleteTransaction(id: String, context: Context) {
        viewModelScope.launch {
            try {
                repository.deleteTransaction(id)
                loadAll(context)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun clearError() { _error.value = null }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun rebuild(all: List<Transaction>) {
        _summary.value  = buildSummary(all)
        _filtered.value = applyFilters(all)
    }

    private fun applyFilters(all: List<Transaction>): List<Transaction> {
        val byTime = when (activeTimeFilter) {
            "Daily"  -> filterDaily(all)
            "Weekly" -> filterWeekly(all)
            else     -> filterMonthly(all)
        }
        return if (selectedCategory == "All") byTime
        else byTime.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }

    private fun filterDaily(list: List<Transaction>): List<Transaction> {
        val cal   = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val year  = cal.get(Calendar.YEAR)
        return list.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.DAY_OF_YEAR) == today && c.get(Calendar.YEAR) == year
        }
    }

    private fun filterWeekly(list: List<Transaction>): List<Transaction> {
        val weekStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return list.filter { it.timestamp >= weekStart }
    }

    private fun filterMonthly(list: List<Transaction>): List<Transaction> =
        list.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == selectedMonth && c.get(Calendar.YEAR) == selectedYear
        }

    private fun buildSummary(all: List<Transaction>): ExpenseSummary {
        val monthly = all.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.MONTH) == selectedMonth && c.get(Calendar.YEAR) == selectedYear
        }

        val spend  = monthly.filter { it.isDebit() }.sumOf { it.amount }
        val income = monthly.filter { it.isCredit() }.sumOf { it.amount }
        val budget = monthlyBudgetLimit

        // Category breakdown: include ALL transactions (debit + credit)
        // so every categorised transaction's amount is counted
        val total = monthly.sumOf { it.amount }.coerceAtLeast(1.0)
        val stats = monthly.groupBy { it.category }
            .map { (cat, txns) ->
                val amt = txns.sumOf { it.amount }
                CategoryStat(cat, amt, ((amt / total) * 100).toInt(), txns.size)
            }
            .sortedByDescending { it.amount }

        return ExpenseSummary(spend, income, budget, stats)
    }

    companion object {
        private const val TAG = "ExpenseVM"

        /** In-memory cache so other activities can read current spending without a full reload */
        var cachedTransactions: List<Transaction> = emptyList()
            private set
    }
}
