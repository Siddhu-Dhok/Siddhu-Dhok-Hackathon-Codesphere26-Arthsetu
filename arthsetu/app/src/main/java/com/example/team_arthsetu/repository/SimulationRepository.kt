package com.example.team_arthsetu.repository

import com.example.team_arthsetu.model.SimResult
import com.example.team_arthsetu.model.SimulationRecord
import com.example.team_arthsetu.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Firestore-backed saved simulations under `users/{uid}/simulations`. */
class SimulationRepository {

    private val auth   = FirebaseAuth.getInstance()
    private val db     = FirebaseFirestore.getInstance()
    private val userId get() = auth.currentUser?.uid ?: ""

    private fun simulationsCol(): CollectionReference? {
        if (userId.isEmpty()) return null
        return db.collection(Constants.USERS).document(userId).collection(Constants.SUB_SIMULATIONS)
    }

    // ── Save — users/{uid}/simulations/{autoId} ─────────────────────────────

    suspend fun save(result: SimResult, inputs: Map<String, Any>) {
        if (userId.isEmpty()) return

        val record = mapOf(
            "userId"           to userId,
            "scenarioType"     to result.scenarioType,
            "inputs"           to inputs,
            "survivalMonths"   to result.survivalMonths,
            "survived"         to result.survived,
            "depletionDate"    to (result.depletionDate ?: ""),
            "debtRequired"     to result.debtRequired,
            "expenseCutNeeded" to result.requiredExpenseCut,
            "insights"         to result.insights,
            "timestamp"        to result.timestamp
        )

        val col = simulationsCol() ?: return
        col.add(record).await()
    }

    // ── History — all docs under current user’s simulations subcollection ──

    suspend fun getHistory(): List<SimulationRecord> {
        val col = simulationsCol() ?: return emptyList()
        return col.get().await()
            .documents.mapNotNull { doc ->
                doc.toObject(SimulationRecord::class.java)?.copy(id = doc.id)
            }
            .sortedByDescending { it.timestamp }
    }

    /** All saved runs for one scenario type (matches [SimResult.scenarioType] / Firestore `scenarioType`). */
    suspend fun getSimulationsForScenario(scenarioType: String): List<SimulationRecord> {
        val col = simulationsCol() ?: return emptyList()
        return try {
            col.whereEqualTo("scenarioType", scenarioType)
                .get().await()
                .documents.mapNotNull { doc ->
                    doc.toObject(SimulationRecord::class.java)?.copy(id = doc.id)
                }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
