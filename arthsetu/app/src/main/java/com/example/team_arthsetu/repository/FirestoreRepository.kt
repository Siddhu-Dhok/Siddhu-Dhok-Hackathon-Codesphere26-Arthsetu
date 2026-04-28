package com.example.team_arthsetu.repository

import android.content.Context
import android.util.Log
import com.example.team_arthsetu.ArthApplication
import com.example.team_arthsetu.bankstatement.StatementTransaction
import com.example.team_arthsetu.model.Goal
import com.example.team_arthsetu.model.Loan
import com.example.team_arthsetu.model.MerchantCategoryMapping
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.model.User
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.CategoryLimitStore
import com.example.team_arthsetu.utils.ExpenseLimitStore
import com.example.team_arthsetu.utils.InsightGenerator
import com.example.team_arthsetu.utils.MerchantNormalizer
import com.example.team_arthsetu.utils.NetworkUnavailableException
import com.example.team_arthsetu.utils.NetworkUtils
import com.example.team_arthsetu.utils.RiskCalculator
import com.example.team_arthsetu.viewmodel.WealthSummary
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.example.team_arthsetu.fcm.FcmTokenSync
import kotlinx.coroutines.tasks.await

class FirestoreRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val currentUserId: String? get() = auth.currentUser?.uid

    private fun resolveContext(context: Context?): Context? =
        context ?: try {
            ArthApplication.appContext
        } catch (_: UninitializedPropertyAccessException) {
            null
        }

    /**
     * @param throwIfOffline if true, throws [NetworkUnavailableException] when there is no validated network.
     */
    private suspend fun guardWrite(
        operation: String,
        context: Context?,
        throwIfOffline: Boolean,
        block: suspend () -> Unit
    ) {
        val ctx = resolveContext(context)
        val online = ctx?.let { NetworkUtils.isInternetAvailable(it) } ?: true
        Log.d(TAG, "Internet available: ${if (ctx == null) "unknown" else online}")
        if (ctx != null && !online) {
            Log.w(TAG, "Firestore write skipped - no internet ($operation)")
            if (throwIfOffline) throw NetworkUnavailableException()
            return
        }
        Log.i(TAG, "Firestore write started: $operation")
        try {
            block()
            Log.i(TAG, "Firestore write success: $operation")
        } catch (e: Exception) {
            Log.e(TAG, "Firestore write failed: $operation — ${e.message}", e)
            throw e
        }
    }

    private fun userTransactions(): CollectionReference? {
        val uid = currentUserId ?: return null
        return db.collection(Constants.USERS).document(uid).collection(Constants.SUB_TRANSACTIONS)
    }

    private fun userLoans(): CollectionReference? {
        val uid = currentUserId ?: return null
        return db.collection(Constants.USERS).document(uid).collection(Constants.SUB_LOANS)
    }

    private fun userGoals(): CollectionReference? {
        val uid = currentUserId ?: return null
        return db.collection(Constants.USERS).document(uid).collection(Constants.SUB_GOALS)
    }

    private fun userMerchantCategories(): CollectionReference? {
        val uid = currentUserId ?: return null
        return db.collection(Constants.USERS).document(uid).collection(Constants.SUB_MERCHANT_CATEGORIES)
    }

    // ─── Transactions — users/{uid}/transactions/{id} ────────────────────────

    /** Add a new transaction with an auto-generated Firestore ID. */
    suspend fun addTransaction(transaction: Transaction) {
        val userId = currentUserId ?: return
        val col = userTransactions() ?: return
        guardWrite("addTransaction", null, throwIfOffline = false) {
            val doc = col.document()
            doc.set(transaction.copy(id = doc.id, userId = userId)).await()
        }
    }

    /**
     * Save a transaction using its existing ID as the Firestore document ID.
     * This makes the write **idempotent** — saving the same SMS twice
     * overwrites the document instead of creating a duplicate.
     */
    suspend fun addTransactionWithId(transaction: Transaction) {
        val userId = currentUserId ?: return
        val col = userTransactions() ?: return
        guardWrite("addTransactionWithId", null, throwIfOffline = false) {
            col.document(transaction.id)
                .set(transaction.copy(userId = userId), SetOptions.merge())
                .await()
        }
    }

    /**
     * Inserts [transaction] at [Transaction.id] atomically (single transaction: no read→write race).
     * Mobile Firestore SDK does not expose [DocumentReference.create]; this uses [FirebaseFirestore.runTransaction]
     * with the same semantics: write only if the document does not exist; otherwise return `false`.
     */
    suspend fun addTransactionWithIdIfAbsent(transaction: Transaction): Boolean {
        val userId = currentUserId ?: throw IllegalStateException("Not signed in")
        val col = userTransactions() ?: throw IllegalStateException("No transactions collection")
        val ref = col.document(transaction.id)
        val ctx = resolveContext(null)
        val online = ctx?.let { NetworkUtils.isInternetAvailable(it) } ?: true
        Log.d(TAG, "Internet available: ${if (ctx == null) "unknown" else online}")
        if (ctx != null && !online) {
            Log.w(TAG, "Firestore write skipped - no internet (addTransactionWithIdIfAbsent)")
            throw NetworkUnavailableException()
        }
        Log.i(TAG, "Firestore write started: addTransactionWithIdIfAbsent transactionId=${transaction.id}")
        val payload = transaction.copy(userId = userId)
        return try {
            val inserted = db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (snap.exists()) {
                    false
                } else {
                    tx.set(ref, payload)
                    true
                }
            }.await()
            if (inserted) {
                Log.i(TAG, "Inserted new transaction transactionId=${transaction.id}")
            } else {
                Log.w(TAG, "Duplicate skipped (document already exists) transactionId=${transaction.id}")
            }
            inserted
        } catch (e: Exception) {
            Log.e(TAG, "Firestore write failed: addTransactionWithIdIfAbsent — ${e.message}", e)
            throw e
        }
    }

    /** Update just the category field of a transaction. */
    suspend fun updateTransactionCategory(transactionId: String, category: String) {
        val col = userTransactions() ?: return
        guardWrite("updateTransactionCategory", null, throwIfOffline = false) {
            col.document(transactionId)
                .update("category", category)
                .await()
        }
    }

    /** Requirement-facing function: update one transaction category. */
    suspend fun updateSingleTransaction(transactionId: String, category: String) {
        updateTransactionCategory(transactionId, category)
    }

    /**
     * Requirement-facing function: update all existing transactions for one merchant.
     * Merchant is normalized with trim().uppercase() before query.
     */
    suspend fun updateCategoryForAllTransactions(merchant: String, category: String) {
        val col = userTransactions() ?: return
        val merchantNorm = merchant.trim().uppercase()
        val loose = MerchantNormalizer.looseKey(merchant)
        if (merchantNorm.isBlank() && loose.isBlank()) return
        if (category.isBlank()) return

        guardWrite("updateCategoryForAllTransactions", null, throwIfOffline = false) {
            val byId = linkedMapOf<String, DocumentSnapshot>()

            fun considerDebit(d: DocumentSnapshot) {
                if (d.getString(Constants.FIELD_TRANSACTION_TYPE)
                        ?.equals(Constants.DEBIT, ignoreCase = true) != true
                ) {
                    return
                }
                byId[d.id] = d
            }

            if (loose.isNotBlank()) {
                col.whereEqualTo(Constants.FIELD_TRANSACTION_MERCHANT_KEY, loose)
                    .get().await().documents.forEach(::considerDebit)
            }
            if (merchantNorm.isNotBlank()) {
                col.whereEqualTo(Constants.FIELD_TRANSACTION_MERCHANT, merchantNorm)
                    .get().await().documents.forEach(::considerDebit)
            }

            if (byId.isEmpty()) {
                Log.i(
                    TAG,
                    "updateCategoryForAllTransactions: 0 debit rows merchantNorm=$merchantNorm looseKey=$loose"
                )
                return@guardWrite
            }

            Log.i(
                TAG,
                "updateCategoryForAllTransactions: updating ${byId.size} debit row(s) merchantNorm=$merchantNorm looseKey=$loose"
            )

            byId.values.chunked(BULK_CATEGORY_UPDATE_BATCH).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { doc ->
                    batch.update(doc.reference, Constants.FIELD_CATEGORY, category)
                }
                batch.commit().await()
            }
        }
    }

    /** Requirement-facing alias: update all transactions by merchant key/name. */
    suspend fun updateAllTransactionsForMerchant(merchantKey: String, category: String) {
        updateCategoryForAllTransactions(merchantKey, category)
    }

    suspend fun getTransactions(): List<Transaction> {
        val col = userTransactions() ?: return emptyList()
        val snapshot = col.get().await()
        return snapshot.toObjects(Transaction::class.java)
            .sortedByDescending { it.timestamp }
    }

    /**
     * Live updates for `users/{uid}/transactions`. Newest first.
     * Remove the returned registration when the screen/view-model is destroyed.
     */
    fun listenToTransactions(
        onUpdate: (List<Transaction>, Exception?) -> Unit
    ): ListenerRegistration? {
        val col = userTransactions() ?: run {
            onUpdate(emptyList(), null)
            return null
        }
        return col.addSnapshotListener { snapshot, e ->
            if (e != null) {
                onUpdate(emptyList(), e)
                return@addSnapshotListener
            }
            val list = snapshot?.toObjects(Transaction::class.java)
                ?.sortedByDescending { it.timestamp } ?: emptyList()
            onUpdate(list, null)
        }
    }

    /**
     * Cloud Functions read limits + [fcmToken] from `users/{uid}` when a transaction is created.
     * Call **before** writing a new transaction (e.g. SMS) so the backend sees current caps and can push FCM.
     */
    suspend fun pushLimitSettingsAndPendingFcmBeforeTxnWrite(context: Context) {
        if (currentUserId == null) return
        try {
            syncExpenseLimitsFromLocal(context)
            syncCategoryLimitsFromLocal(context)
        } catch (_: Exception) {
        }
        try {
            FcmTokenSync.flushPendingTokenIfSignedIn(context.applicationContext)
        } catch (_: Exception) {
        }
    }

    suspend fun deleteTransaction(transactionId: String) {
        val uid = currentUserId ?: return
        guardWrite("deleteTransaction", null, throwIfOffline = false) {
            db.collection(Constants.USERS).document(uid)
                .collection(Constants.SUB_TRANSACTIONS)
                .document(transactionId)
                .delete()
                .await()
        }
    }

    // ─── Loans — users/{uid}/loans/{id} ────────────────────────────────────

    suspend fun addLoan(loan: Loan) {
        val userId = currentUserId ?: return
        val col = userLoans() ?: return
        guardWrite("addLoan", null, throwIfOffline = false) {
            val doc = col.document()
            doc.set(loan.copy(id = doc.id, userId = userId)).await()
        }
    }

    suspend fun getLoans(): List<Loan> {
        val col = userLoans() ?: return emptyList()
        return col.get().await().toObjects(Loan::class.java)
    }

    // ─── Goals — users/{uid}/goals/{goalId} ─────────────────────────────────

    /**
     * Persists a goal; `createdAt` is set server-side. Document ID is auto-generated.
     */
    suspend fun saveGoal(goal: Goal) {
        val userId = currentUserId ?: return
        val col = userGoals() ?: return
        guardWrite("saveGoal", null, throwIfOffline = false) {
            val doc = col.document()
            val data = hashMapOf<String, Any>(
                "userId" to userId,
                "goalName" to goal.goalName,
                "description" to goal.description,
                "targetAmount" to goal.targetAmount,
                "currentAmount" to goal.currentAmount,
                "targetDate" to goal.targetDate,
                "aiInsight" to goal.aiInsight,
                "createdAt" to FieldValue.serverTimestamp()
            )
            doc.set(data).await()
        }
    }

    suspend fun getGoals(): List<Goal> {
        val col = userGoals() ?: return emptyList()
        return col.get().await().documents
            .mapNotNull { parseGoalDocument(it) }
            .sortedByDescending { it.createdAt }
    }

    /** Fetch one goal by id (for detail screen). */
    suspend fun getGoal(goalId: String): Goal? {
        val col = userGoals() ?: return null
        val doc = col.document(goalId).get().await()
        if (!doc.exists()) return null
        return parseGoalDocument(doc)
    }

    private fun parseGoalDocument(doc: DocumentSnapshot): Goal? {
        val createdMs = doc.getTimestamp("createdAt")?.toDate()?.time
            ?: doc.getLong("createdAt")
            ?: 0L
        val targetDateMs = doc.getLong("targetDate")
            ?: (doc.getTimestamp("targetDate")?.toDate()?.time ?: 0L)
        return Goal(
            id = doc.id,
            userId = doc.getString("userId").orEmpty(),
            goalName = doc.getString("goalName") ?: doc.getString("name").orEmpty(),
            description = doc.getString("description").orEmpty(),
            targetAmount = doc.getDouble("targetAmount") ?: 0.0,
            currentAmount = doc.getDouble("currentAmount") ?: 0.0,
            targetDate = targetDateMs,
            aiInsight = doc.getString("aiInsight").orEmpty(),
            createdAt = createdMs
        )
    }

    // ─── User profile — users/{uid} (document) ───────────────────────────────

    suspend fun saveUser(user: User) {
        val userId = currentUserId ?: return
        guardWrite("saveUser", null, throwIfOffline = false) {
            db.collection(Constants.USERS).document(userId).set(user).await()
        }
    }

    suspend fun getUser(): User? {
        val userId = currentUserId ?: return null
        val doc = db.collection(Constants.USERS).document(userId).get().await()
        return doc.toObject(User::class.java)
    }

    /**
     * Hackathon AI: totals, risk score/level, and insights merged into `users/{uid}`.
     */
    suspend fun syncAiFinancialMetrics(
        transactions: List<Transaction>,
        wealth: WealthSummary? = null
    ) {
        val userId = currentUserId ?: return
        val wealthIn = wealth?.let {
            RiskCalculator.WealthInputs(it.netWorth, it.totalAssets, it.totalLiabilities)
        }
        val risk = RiskCalculator.compute(transactions, wealthIn)
        val insights = InsightGenerator.generate(transactions, risk, wealthIn)
        val payload = buildMap {
            put(Constants.FIELD_AI_TOTAL_EXPENSE, risk.totalExpense)
            put(Constants.FIELD_AI_TOTAL_INCOME, risk.totalIncome)
            put(Constants.FIELD_AI_SAVINGS, risk.savings)
            put(Constants.FIELD_AI_RISK_SCORE, risk.riskScore)
            put(Constants.FIELD_AI_RISK_LEVEL, risk.riskLevel)
            put(Constants.FIELD_AI_INSIGHTS, insights)
            put(Constants.FIELD_AI_UPDATED_AT, FieldValue.serverTimestamp())
            if (wealth != null) {
                put(Constants.FIELD_AI_WEALTH_NET_WORTH, wealth.netWorth)
                put(Constants.FIELD_AI_WEALTH_ASSETS, wealth.totalAssets)
                put(Constants.FIELD_AI_WEALTH_LIABILITIES, wealth.totalLiabilities)
            }
        }
        guardWrite("syncAiFinancialMetrics", null, throwIfOffline = false) {
            db.collection(Constants.USERS).document(userId)
                .set(payload, SetOptions.merge())
                .await()
        }
    }

    /** Reads merged AI fields from `users/{uid}` (see [syncAiFinancialMetrics]). */
    suspend fun fetchAiDashboardMetrics(): Triple<Double?, String?, List<String>> {
        val uid = currentUserId ?: return Triple(null, null, emptyList())
        val snap = db.collection(Constants.USERS).document(uid).get().await()
        if (!snap.exists()) return Triple(null, null, emptyList())
        val score = snap.getDouble(Constants.FIELD_AI_RISK_SCORE)
        val level = snap.getString(Constants.FIELD_AI_RISK_LEVEL)
        val raw = snap.get(Constants.FIELD_AI_INSIGHTS)
        val insights = when (raw) {
            is List<*> -> raw.mapNotNull { it as? String }
            else -> emptyList()
        }
        return Triple(score, level, insights)
    }

    /**
     * Pushes local expense limits to `users/{uid}` so Cloud Functions can compare
     * against new transactions and send FCM when exceeded.
     */
    suspend fun syncExpenseLimitsFromLocal(context: Context) {
        val userId = currentUserId ?: return
        val app = context.applicationContext
        val payload = mapOf(
            Constants.FIELD_EXPENSE_LIMIT_DAILY to ExpenseLimitStore.getDailyLimit(app),
            Constants.FIELD_EXPENSE_LIMIT_WEEKLY to ExpenseLimitStore.getWeeklyLimit(app),
            Constants.FIELD_EXPENSE_LIMIT_MONTHLY to ExpenseLimitStore.getMonthlyLimit(app),
            Constants.FIELD_EXPENSE_LIMIT_QUARTERLY to ExpenseLimitStore.getQuarterlyLimit(app),
            "expenseLimitsUpdatedAt" to FieldValue.serverTimestamp()
        )
        guardWrite("syncExpenseLimitsFromLocal", app, throwIfOffline = false) {
            db.collection(Constants.USERS).document(userId)
                .set(payload, SetOptions.merge())
                .await()
        }
    }

    /**
     * Pushes [CategoryLimitStore] map to Firestore so Cloud Functions can compare monthly
     * category spend to caps when new transactions are written.
     */
    suspend fun syncCategoryLimitsFromLocal(context: Context) {
        val userId = currentUserId ?: return
        val app = context.applicationContext
        val limits = CategoryLimitStore.getAllLimits(app)
        guardWrite("syncCategoryLimitsFromLocal", app, throwIfOffline = false) {
            db.collection(Constants.USERS).document(userId)
                .set(
                    mapOf(
                        Constants.FIELD_CATEGORY_LIMITS to limits,
                        "categoryLimitsUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
        }
    }

    // Production architecture: totals/limit checks are backend-driven (Cloud Functions).
    // Intentionally no "fetch full history + compute totals" here.

    /** Store FCM token for targeted push from backend. */
    suspend fun updateFcmToken(token: String) {
        val userId = currentUserId ?: return
        guardWrite("updateFcmToken", null, throwIfOffline = false) {
            db.collection(Constants.USERS).document(userId)
                .set(
                    mapOf("fcmToken" to token, "fcmTokenUpdatedAt" to FieldValue.serverTimestamp()),
                    SetOptions.merge()
                )
                .await()
        }
    }

    // ─── Merchant learning — users/{uid}/merchant_categories/{docId} ─────────

    /**
     * Exact match on normalized merchant.
     *
     * Supports BOTH document id styles:
     * - New/simple: docId = normalized merchant string (uppercase)
     * - Legacy: docId = sha256(normalized merchant) prefix
     */
    suspend fun getMerchantCategoryExact(normalizedMerchant: String): String? {
        if (normalizedMerchant.isBlank()) return null
        val col = userMerchantCategories() ?: return null
        // 1) Preferred: docId = merchantName (uppercase normalized)
        col.document(normalizedMerchant).get().await().let { snap ->
            if (snap.exists()) return snap.getString(Constants.FIELD_CATEGORY)
        }
        // 2) Backward compat: hashed doc id
        val hashed = MerchantNormalizer.firestoreDocumentId(normalizedMerchant)
        val snap = col.document(hashed).get().await()
        if (!snap.exists()) return null
        return snap.getString(Constants.FIELD_CATEGORY)
    }

    suspend fun loadMerchantMappingsForCache(): List<Pair<String, String>> {
        val col = userMerchantCategories() ?: return emptyList()
        return col.get().await().documents.mapNotNull { d ->
            val m = d.getString(Constants.FIELD_MERCHANT_NAME) ?: return@mapNotNull null
            val c = d.getString(Constants.FIELD_CATEGORY) ?: return@mapNotNull null
            m to c
        }
    }

    /** Fetches all unique user categories from users/{uid}/merchant_categories. */
    suspend fun getAllMerchantCategories(): List<String> {
        val col = userMerchantCategories() ?: return emptyList()
        return col.get().await().documents
            .mapNotNull { it.getString(Constants.FIELD_CATEGORY)?.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.uppercase() }
            .sortedBy { it.uppercase() }
    }

    /** Fetches all unique normalized merchant names from users/{uid}/merchant_categories. */
    suspend fun getAllMappedMerchants(): List<String> {
        val col = userMerchantCategories() ?: return emptyList()
        return col.get().await().documents
            .mapNotNull { it.getString(Constants.FIELD_MERCHANT_NAME)?.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.uppercase() }
            .sortedBy { it.uppercase() }
    }

    /**
     * Best partial match against stored [MerchantCategoryMapping.merchantName] values.
     * Uses in-memory cache per user; call [saveMerchantCategoryMapping] to invalidate.
     */
    suspend fun findMerchantCategoryPartial(merchantRaw: String): String? {
        if (merchantRaw.trim().length < MerchantNormalizer.FUZZY_MERCHANT_MIN_LEN) return null
        val uid = currentUserId ?: return null
        val normalizedMerchant = MerchantNormalizer.normalize(merchantRaw)
        var rows = MerchantCategoryRemoteCache.getCached(uid)
        if (rows == null) {
            rows = loadMerchantMappingsForCache()
            MerchantCategoryRemoteCache.setCached(uid, rows)
        }
        var best: Pair<Int, String>? = null
        for ((stored, cat) in rows) {
            val sNorm = if (normalizedMerchant.isNotBlank()) {
                MerchantNormalizer.partialMatchScore(normalizedMerchant, stored)
            } else {
                0
            }
            val sKey = MerchantNormalizer.strictKeyMatchScore(merchantRaw, stored)
            val score = maxOf(sNorm, sKey)
            if (score > 0 && (best == null || score > best.first)) {
                best = score to cat
            }
        }
        return best?.second
    }

    /**
     * Resolves learned category: [MerchantNormalizer.looseKey] doc, [MerchantNormalizer.strictKey] doc,
     * legacy spaced [MerchantNormalizer.normalize], [MerchantNormalizer.merchantsMatchForCategory], then fuzzy partial.
     */
    suspend fun findLearnedMerchantCategory(merchantRaw: String): String? {
        if (merchantRaw.isBlank()) return null
        val loose = MerchantNormalizer.looseKey(merchantRaw)
        if (loose.isNotBlank()) {
            getMerchantCategoryExact(loose)?.let { return it }
        }
        val strict = MerchantNormalizer.strictKey(merchantRaw)
        if (strict.isNotBlank() && strict != loose) {
            getMerchantCategoryExact(strict)?.let { return it }
        }
        val spaced = MerchantNormalizer.normalize(merchantRaw)
        if (spaced.isNotBlank()) {
            getMerchantCategoryExact(spaced)?.let { return it }
        }
        val uid = currentUserId ?: return null
        var rows = MerchantCategoryRemoteCache.getCached(uid)
        if (rows == null) {
            rows = loadMerchantMappingsForCache()
            MerchantCategoryRemoteCache.setCached(uid, rows)
        }
        for ((stored, cat) in rows) {
            if (MerchantNormalizer.merchantsMatchForCategory(merchantRaw, stored)) return cat
        }
        return findMerchantCategoryPartial(merchantRaw)
    }

    suspend fun saveMerchantCategoryMapping(merchantRaw: String, category: String) {
        val uid = currentUserId ?: return
        val loose = MerchantNormalizer.looseKey(merchantRaw)
        val strict = MerchantNormalizer.strictKey(merchantRaw)
        if (loose.isBlank() && strict.isBlank()) return
        if (category.isBlank()) return
        val col = userMerchantCategories() ?: return
        val canonicalName = loose.ifBlank { strict }
        val mapping = MerchantCategoryMapping(
            merchantName = canonicalName,
            category = category,
            userId = uid
        )
        guardWrite("saveMerchantCategoryMapping", null, throwIfOffline = false) {
            if (loose.isNotBlank()) {
                col.document(loose).set(mapping).await()
                col.document(MerchantNormalizer.firestoreDocumentId(loose)).set(mapping).await()
            }
            if (strict.isNotBlank() && strict != loose) {
                col.document(strict).set(mapping).await()
            }
            MerchantCategoryRemoteCache.invalidate(uid)
        }
    }

    /** Requirement-facing alias: saves users/{uid}/merchant_categories/{MERCHANT_UPPER}.category */
    suspend fun saveMerchantCategory(merchant: String, category: String) {
        saveMerchantCategoryMapping(merchant, category)
    }

    /** Requirement-facing function name. */
    suspend fun saveMerchantMapping(merchant: String, category: String) {
        saveMerchantCategoryMapping(merchant, category)
    }

    /**
     * Written when a transaction could not be auto-categorised; a Cloud Function can
     * read this and send an FCM data message (e.g. `type=merchant_category`).
     */
    /**
     * Optional archive of bank-statement imports (separate from SMS [SUB_TRANSACTIONS]).
     */
    suspend fun saveStatementTransactions(context: Context?, items: List<StatementTransaction>) {
        val uid = currentUserId ?: return
        if (items.isEmpty()) return
        guardWrite("saveStatementTransactions", context, throwIfOffline = false) {
            val col = db.collection(Constants.USERS).document(uid)
                .collection(Constants.SUB_STATEMENT_TRANSACTIONS)
            var batch = db.batch()
            var count = 0
            val baseTs = System.currentTimeMillis()
            var i = 0
            for (t in items) {
                // Client timestamp used for dedup + ordering on retrieval.
                val clientImportedAt = baseTs + i
                i++
                val ref = col.document(clientImportedAt.toString())
                batch.set(
                    ref,
                    mapOf(
                        "merchant" to t.merchant,
                        "amount" to t.amount,
                        "type" to t.type,
                        "date" to t.date,
                        "balance" to t.balance,
                        "category" to t.category,
                        "narration" to t.narration,
                        "refNo" to t.refNo,
                        "clientImportedAt" to clientImportedAt,
                        "importedAt" to FieldValue.serverTimestamp()
                    )
                )
                count++
                if (count >= 450) {
                    batch.commit().await()
                    batch = db.batch()
                    count = 0
                }
            }
            if (count > 0) batch.commit().await()
        }
    }

    /**
     * Loads bank statement transactions saved in Firestore:
     * users/{uid}/statement_transactions
     *
     * Dedupe: by (clientImportedAt + date + amount + merchant).
     */
    suspend fun getStatementTransactionsFromCloud(limit: Long = 2000): List<StatementTransaction> {
        val uid = currentUserId ?: return emptyList()
        val col = db.collection(Constants.USERS).document(uid)
            .collection(Constants.SUB_STATEMENT_TRANSACTIONS)

        val snap = try {
            // Preferred (new docs): stable ordering + easy dedupe.
            col.orderBy("clientImportedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
        } catch (_: Exception) {
            try {
                // Fallback (older docs): server timestamp.
                col.orderBy("importedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()
            } catch (_: Exception) {
                // Last resort: no ordering (still returns docs, then we dedupe client-side).
                col.limit(limit).get().await()
            }
        }

        val out = mutableListOf<StatementTransaction>()
        val seen = HashSet<String>()
        for (doc in snap.documents) {
            val merchant = doc.getString("merchant") ?: continue
            val amount = doc.getDouble("amount") ?: continue
            val type = doc.getString("type") ?: StatementTransaction.TYPE_DEBIT
            val date = doc.getString("date") ?: ""
            val balance = doc.getDouble("balance")
            val category = doc.getString("category") ?: "Uncategorized"
            val narration = doc.getString("narration") ?: ""
            val refNo = doc.getString("refNo") ?: ""
            val clientImportedAt = doc.getLong("clientImportedAt") ?: 0L
            val key = if (clientImportedAt > 0) {
                "$clientImportedAt|$date|$amount|$merchant"
            } else {
                // Older docs without clientImportedAt: dedupe by stable content.
                "$date|$amount|$merchant|$type"
            }
            if (!seen.add(key)) continue
            out.add(
                StatementTransaction(
                    merchant = merchant,
                    amount = amount,
                    type = type,
                    date = date,
                    balance = balance,
                    category = category,
                    narration = narration,
                    refNo = refNo
                )
            )
        }
        return out
    }

    suspend fun enqueueMerchantCategoryLearningRequest(txn: Transaction) {
        if (!txn.type.equals(Constants.DEBIT, ignoreCase = true)) return
        val uid = currentUserId ?: return
        val norm = MerchantNormalizer.normalize(txn.merchant)
        val loose = MerchantNormalizer.looseKey(txn.merchant)
        guardWrite("enqueueMerchantCategoryLearningRequest", null, throwIfOffline = false) {
            db.collection(Constants.USERS).document(uid)
                .collection(Constants.SUB_MERCHANT_CATEGORY_REQUESTS)
                .document(txn.id)
                .set(
                    mapOf(
                        "txnId" to txn.id,
                        Constants.FIELD_MERCHANT_NAME to norm,
                        "merchantLooseKey" to loose,
                        "merchantRaw" to txn.merchant,
                        "userId" to uid,
                        "amount" to txn.amount,
                        "txnType" to txn.type,
                        "notifySource" to "client",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        }
    }

    companion object {
        private const val TAG = "FirestoreRepository"
        private const val BULK_CATEGORY_UPDATE_BATCH = 200
    }
}
