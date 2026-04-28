package com.example.team_arthsetu.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.InsightGenerator
import com.example.team_arthsetu.utils.RiskCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardSummary(
    val totalSpent: Double = 0.0,
    val totalIncome: Double = 0.0,
    val recentTransactions: List<Transaction> = emptyList()
)

data class AiDashboardMetrics(
    val riskScore: Double?,
    val riskLevel: String?,
    val insights: List<String>
)

class DashboardViewModel : ViewModel() {

    private val repository = FirestoreRepository()

    /** Last known wealth from the UI; used when expense list updates without a new [WealthSummary]. */
    private var cachedWealth: WealthSummary? = null

    private val _summary = MutableLiveData<DashboardSummary>()
    val summary: LiveData<DashboardSummary> = _summary

    private val _aiMetrics = MutableLiveData<AiDashboardMetrics?>()
    val aiMetrics: LiveData<AiDashboardMetrics?> = _aiMetrics

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private fun wealthInputs(w: WealthSummary?): RiskCalculator.WealthInputs? =
        w?.let { RiskCalculator.WealthInputs(it.netWorth, it.totalAssets, it.totalLiabilities) }

    private fun applyTransactionSummary(transactions: List<Transaction>) {
        val spent = transactions.filter { it.isDebit() }.sumOf { it.amount }
        val income = transactions.filter { it.isCredit() }.sumOf { it.amount }
        _summary.value = DashboardSummary(
            totalSpent = spent,
            totalIncome = income,
            recentTransactions = transactions.take(5)
        )
    }

    private fun publishAiLocal(transactions: List<Transaction>, wealth: WealthSummary?) {
        val wIn = wealthInputs(wealth)
        val risk = RiskCalculator.compute(transactions, wIn)
        val insights = InsightGenerator.generate(transactions, risk, wIn)
        _aiMetrics.value = AiDashboardMetrics(risk.riskScore, risk.riskLevel, insights)
    }

    /**
     * Full reload (shows loading). Uses cached wealth if the wealth observer has not run yet.
     */
    fun loadSummary() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val transactions = ExpenseViewModel.cachedTransactions
                    .takeIf { it.isNotEmpty() }
                    ?: repository.getTransactions()

                applyTransactionSummary(transactions)
                publishAiLocal(transactions, cachedWealth)

                withContext(Dispatchers.IO) {
                    try {
                        repository.syncAiFinancialMetrics(transactions, cachedWealth)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                _summary.value = DashboardSummary()
                _aiMetrics.value = null
            }
            _isLoading.value = false
        }
    }

    /**
     * Recomputes dashboard summary + AI risk from current transactions and wealth without toggling loading.
     * Call when expenses or wealth change so the risk card updates immediately (no stale Firestore read).
     */
    fun publishFrom(transactions: List<Transaction>, wealth: WealthSummary?) {
        if (wealth != null) cachedWealth = wealth
        viewModelScope.launch {
            try {
                applyTransactionSummary(transactions)
                val w = wealth ?: cachedWealth
                publishAiLocal(transactions, w)
                withContext(Dispatchers.IO) {
                    try {
                        repository.syncAiFinancialMetrics(transactions, w)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
