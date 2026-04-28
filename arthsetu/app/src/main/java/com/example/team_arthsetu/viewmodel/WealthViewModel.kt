package com.example.team_arthsetu.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_arthsetu.model.Asset
import com.example.team_arthsetu.model.Liability
import com.example.team_arthsetu.repository.WealthRepository
import kotlinx.coroutines.launch

// ── Data classes ───────────────────────────────────────────────────────────────

data class WealthSummary(
    val netWorth         : Double              = 0.0,
    val totalAssets      : Double              = 0.0,
    val totalLiabilities : Double              = 0.0,
    val bankBalance      : Double              = 0.0,
    val invested         : Double              = 0.0,
    val borrowing        : Double              = 0.0,
    val avgChangePercent : Double              = 0.0,
    val allocation       : List<AllocationSlice> = emptyList()
)

data class AllocationSlice(
    val label  : String,
    val amount : Double,
    val percent: Float,
    val color  : Int
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class WealthViewModel : ViewModel() {

    private val repository = WealthRepository()

    private val _assets      = MutableLiveData<List<Asset>>(emptyList())
    val assets: LiveData<List<Asset>> = _assets

    private val _liabilities = MutableLiveData<List<Liability>>(emptyList())
    val liabilities: LiveData<List<Liability>> = _liabilities

    private val _summary     = MutableLiveData<WealthSummary>()
    val summary: LiveData<WealthSummary> = _summary

    private val _isLoading   = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error       = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    // ── Load ──────────────────────────────────────────────────────────────────

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val assets      = repository.getAssets()
                val liabilities = repository.getLiabilities()
                _assets.value      = assets
                _liabilities.value = liabilities
                _summary.value     = buildSummary(assets, liabilities)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Add / Delete ──────────────────────────────────────────────────────────

    fun addAsset(asset: Asset, onDone: () -> Unit) {
        viewModelScope.launch {
            try { repository.addAsset(asset); loadAll(); onDone() }
            catch (e: Exception) { _error.value = e.message }
        }
    }

    fun deleteAsset(id: String) {
        viewModelScope.launch {
            try { repository.deleteAsset(id); loadAll() }
            catch (e: Exception) { _error.value = e.message }
        }
    }

    fun addLiability(liability: Liability, onDone: () -> Unit) {
        viewModelScope.launch {
            try { repository.addLiability(liability); loadAll(); onDone() }
            catch (e: Exception) { _error.value = e.message }
        }
    }

    fun deleteLiability(id: String) {
        viewModelScope.launch {
            try { repository.deleteLiability(id); loadAll() }
            catch (e: Exception) { _error.value = e.message }
        }
    }

    fun clearError() { _error.value = null }

    // ── Summary builder ───────────────────────────────────────────────────────

    private fun buildSummary(assets: List<Asset>, liabilities: List<Liability>): WealthSummary {
        val totalAssets      = assets.sumOf { it.value }
        val totalLiabilities = liabilities.sumOf { it.amount }
        val netWorth         = totalAssets - totalLiabilities

        val bankBalance = assets.filter { it.type in listOf("savings","bank","fixed_deposit") }
            .sumOf { it.value }
        val invested    = assets.filter { it.type in listOf("stocks","equity","mutual_fund","gold","crypto","real_estate") }
            .sumOf { it.value }
        val borrowing   = totalLiabilities

        val avgChange = if (assets.isNotEmpty())
            assets.map { it.changePercent }.average() else 0.0

        // Build allocation slices grouped by type category
        val groupMap = linkedMapOf(
            "Stocks"      to 0xFF4C35DC.toInt(),
            "Cash"        to 0xFF00C070.toInt(),
            "Gold"        to 0xFFFF9F0A.toInt(),
            "Crypto"      to 0xFFE91E8C.toInt(),
            "Mutual Funds" to 0xFFFF5722.toInt(),
            "Real Estate" to 0xFF00BCD4.toInt(),
            "Other"       to 0xFF9E9E9E.toInt()
        )

        val grouped = assets.groupBy { assetAllocationGroup(it.type) }
        val allocation = groupMap.keys.mapNotNull { label ->
            val group = grouped[label] ?: return@mapNotNull null
            val amt   = group.sumOf { it.value }
            val pct   = if (totalAssets > 0) ((amt / totalAssets) * 100).toFloat() else 0f
            if (amt > 0) AllocationSlice(label, amt, pct, groupMap[label]!!) else null
        }

        return WealthSummary(netWorth, totalAssets, totalLiabilities,
            bankBalance, invested, borrowing, avgChange, allocation)
    }

    private fun assetAllocationGroup(type: String): String = when (type) {
        "stocks", "equity"         -> "Stocks"
        "savings", "bank",
        "fixed_deposit"            -> "Cash"
        "gold"                     -> "Gold"
        "crypto"                   -> "Crypto"
        "mutual_fund"              -> "Mutual Funds"
        "real_estate"              -> "Real Estate"
        else                       -> "Other"
    }

    // ── Asset / Liability icon helpers ────────────────────────────────────────

    companion object {
        fun assetIcon(type: String) = when (type) {
            "stocks", "equity" -> "📈"
            "mutual_fund"      -> "📊"
            "gold"             -> "🥇"
            "crypto"           -> "🪙"
            "real_estate"      -> "🏠"
            "savings", "bank"  -> "💰"
            "fixed_deposit"    -> "🏦"
            else               -> "💳"
        }

        fun assetColor(type: String) = when (type) {
            "stocks", "equity" -> 0xFF4C35DC.toInt()
            "mutual_fund"      -> 0xFFFF5722.toInt()
            "gold"             -> 0xFFFF9F0A.toInt()
            "crypto"           -> 0xFFE91E8C.toInt()
            "real_estate"      -> 0xFF00BCD4.toInt()
            "savings", "bank",
            "fixed_deposit"    -> 0xFF00C070.toInt()
            else               -> 0xFF9E9E9E.toInt()
        }

        fun liabilityIcon(type: String) = when (type) {
            "home_loan"        -> "🏠"
            "car_loan"         -> "🚗"
            "personal_loan"    -> "👤"
            "credit_card"      -> "💳"
            "education_loan"   -> "🎓"
            else               -> "📄"
        }
    }
}
