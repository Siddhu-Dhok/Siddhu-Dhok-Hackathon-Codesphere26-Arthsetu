package com.example.team_arthsetu.repository

import com.example.team_arthsetu.model.Asset
import com.example.team_arthsetu.model.Liability
import com.example.team_arthsetu.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class WealthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseFirestore.getInstance()

    private val userId get() = auth.currentUser?.uid ?: ""

    private fun assetsCol(): CollectionReference? {
        if (userId.isEmpty()) return null
        return db.collection(Constants.USERS).document(userId).collection(Constants.SUB_ASSETS)
    }

    private fun liabilitiesCol(): CollectionReference? {
        if (userId.isEmpty()) return null
        return db.collection(Constants.USERS).document(userId).collection(Constants.SUB_LIABILITIES)
    }

    // ── Assets — users/{uid}/assets/{id} ───────────────────────────────────

    suspend fun getAssets(): List<Asset> {
        val col = assetsCol() ?: return emptyList()
        return col.get().await()
            .toObjects(Asset::class.java)
            .sortedByDescending { it.timestamp }
    }

    suspend fun addAsset(asset: Asset) {
        val col = assetsCol() ?: return
        val ref = col.document()
        ref.set(asset.copy(id = ref.id, userId = userId)).await()
    }

    suspend fun deleteAsset(id: String) {
        if (userId.isEmpty()) return
        db.collection(Constants.USERS).document(userId)
            .collection(Constants.SUB_ASSETS)
            .document(id)
            .delete()
            .await()
    }

    // ── Liabilities — users/{uid}/liabilities/{id} ─────────────────────────

    suspend fun getLiabilities(): List<Liability> {
        val col = liabilitiesCol() ?: return emptyList()
        return col.get().await()
            .toObjects(Liability::class.java)
            .sortedByDescending { it.timestamp }
    }

    suspend fun addLiability(liability: Liability) {
        val col = liabilitiesCol() ?: return
        val ref = col.document()
        ref.set(liability.copy(id = ref.id, userId = userId)).await()
    }

    suspend fun deleteLiability(id: String) {
        if (userId.isEmpty()) return
        db.collection(Constants.USERS).document(userId)
            .collection(Constants.SUB_LIABILITIES)
            .document(id)
            .delete()
            .await()
    }
}
