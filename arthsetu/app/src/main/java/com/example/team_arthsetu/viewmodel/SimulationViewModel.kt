package com.example.team_arthsetu.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_arthsetu.engine.ScenarioEngine
import com.example.team_arthsetu.model.SimInput
import com.example.team_arthsetu.model.SimResult
import com.example.team_arthsetu.repository.SimulationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimulationViewModel : ViewModel() {

    private val repo = SimulationRepository()

    val result    = MutableLiveData<SimResult?>()
    val isLoading = MutableLiveData(false)
    val isSaved   = MutableLiveData(false)
    val error     = MutableLiveData<String?>()

    // ── Run simulations on a background thread ─────────────────────────────────

    fun runJobLoss(input: SimInput.JobLoss) = simulate {
        ScenarioEngine.simulateJobLoss(input)
    }

    fun runMedical(input: SimInput.MedicalEmergency) = simulate {
        ScenarioEngine.simulateMedical(input)
    }

    fun runIncomeDrop(input: SimInput.IncomeDrop) = simulate {
        ScenarioEngine.simulateIncomeDrop(input)
    }

    fun runInvestmentCrash(input: SimInput.InvestmentCrash) = simulate {
        ScenarioEngine.simulateInvestmentCrash(input)
    }

    fun runEmiRateHike(input: SimInput.EmiRateHike) = simulate {
        ScenarioEngine.simulateEmiRateHike(input)
    }

    private fun simulate(block: () -> SimResult) {
        viewModelScope.launch {
            isLoading.value = true
            result.value    = null
            try {
                val res = withContext(Dispatchers.Default) { block() }
                result.value = res
            } catch (e: Exception) {
                error.value = e.message ?: "Simulation failed"
            } finally {
                isLoading.value = false
            }
        }
    }

    // ── Save to Firebase ───────────────────────────────────────────────────────

    fun saveResult(res: SimResult, inputs: Map<String, Any>) {
        viewModelScope.launch {
            try {
                repo.save(res, inputs)
                isSaved.value = true
            } catch (e: Exception) {
                error.value = "Save failed: ${e.message}"
            }
        }
    }

    fun clearError() { error.value = null }
}
