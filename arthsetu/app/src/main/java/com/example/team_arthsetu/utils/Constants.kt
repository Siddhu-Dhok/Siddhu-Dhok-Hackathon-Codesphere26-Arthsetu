package com.example.team_arthsetu.utils

/**
 * Firestore hierarchy — **user is the main scope** (easy to secure & filter):
 *
 * ```
 * users/{uid}                          ← profile / settings document
 * users/{uid}/transactions/{txId}
 * users/{uid}/assets/{assetId}
 * users/{uid}/liabilities/{liabilityId}
 * users/{uid}/loans/{loanId}
 * users/{uid}/goals/{goalId}
 * users/{uid}/simulations/{simId}
 * ```
 *
 * Queries only need the signed-in user’s `uid`; no separate `whereEqualTo("userId")`
 * on subcollections (path already scopes data). Documents may still store `userId`
 * for exports / analytics.
 *
 * **Security rules (example)** — publish in Firebase Console → Firestore → Rules:
 * ```
 * match /users/{userId} {
 *   allow read, write: if request.auth != null && request.auth.uid == userId;
 *   match /transactions/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /assets/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /liabilities/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /loans/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /goals/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /simulations/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /merchant_categories/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 *   match /merchant_category_requests/{docId} {
 *     allow read, write: if request.auth != null && request.auth.uid == userId;
 *   }
 * }
 * ```
 */
object Constants {

    /** Root collection: one document per user profile */
    const val USERS = "users"

    /** Subcollection names — always under [USERS]/{uid}/… */
    const val SUB_TRANSACTIONS  = "transactions"
    const val SUB_ASSETS        = "assets"
    const val SUB_LIABILITIES   = "liabilities"
    const val SUB_LOANS         = "loans"
    const val SUB_GOALS         = "goals"
    const val SUB_SIMULATIONS   = "simulations"
    /** Bank statement import (separate from SMS [SUB_TRANSACTIONS]) */
    const val SUB_STATEMENT_TRANSACTIONS = "statement_transactions"
    /** Learned merchant → category (fields: [FIELD_MERCHANT_NAME], [FIELD_CATEGORY], userId) */
    const val SUB_MERCHANT_CATEGORIES = "merchant_categories"
    /** Optional queue for Cloud Functions to send FCM when a merchant needs categorization */
    const val SUB_MERCHANT_CATEGORY_REQUESTS = "merchant_category_requests"

    /** Last CATEGORY_REQUIRED FCM time per [merchant mapping key] (server throttle). */
    const val SUB_CATEGORY_FCM_THROTTLE = "category_fcm_throttle"

    // Transaction types
    const val DEBIT  = "debit"
    const val CREDIT = "credit"

    /** [Transaction.source] — bank SMS pipeline */
    const val TXN_SOURCE_SMS = "sms"

    /** [Transaction.source] — user added from bank statement PDF import */
    const val TXN_SOURCE_BANK_PDF = "bank_pdf"

    /** Firestore field names on users/{uid} for server-side limit checks (Cloud Functions) */
    const val FIELD_EXPENSE_LIMIT_DAILY     = "expenseLimitDaily"
    const val FIELD_EXPENSE_LIMIT_WEEKLY    = "expenseLimitWeekly"
    const val FIELD_EXPENSE_LIMIT_MONTHLY   = "expenseLimitMonthly"
    const val FIELD_EXPENSE_LIMIT_QUARTERLY = "expenseLimitQuarterly"

    /** users/{uid}.categoryLimits — map of category name → monthly cap (for Cloud Functions). */
    const val FIELD_CATEGORY_LIMITS = "categoryLimits"

    /** Firestore field on merchant_categories documents */
    const val FIELD_MERCHANT_NAME = "merchantName"
    const val FIELD_CATEGORY = "category"

    /** Firestore fields on [com.example.simple_idea_fin.model.Transaction] documents */
    const val FIELD_TRANSACTION_MERCHANT = "merchant"
    const val FIELD_TRANSACTION_MERCHANT_KEY = "merchantKey"
    const val FIELD_TRANSACTION_TYPE = "type"

    /** Hackathon AI metrics on users/{uid} (merge) */
    const val FIELD_AI_TOTAL_EXPENSE = "aiTotalExpense"
    const val FIELD_AI_TOTAL_INCOME = "aiTotalIncome"
    const val FIELD_AI_SAVINGS = "aiSavings"
    const val FIELD_AI_RISK_SCORE = "aiRiskScore"
    const val FIELD_AI_RISK_LEVEL = "aiRiskLevel"
    const val FIELD_AI_INSIGHTS = "aiInsights"
    const val FIELD_AI_UPDATED_AT = "aiMetricsUpdatedAt"
    const val FIELD_AI_WEALTH_NET_WORTH = "aiWealthNetWorth"
    const val FIELD_AI_WEALTH_ASSETS = "aiWealthAssets"
    const val FIELD_AI_WEALTH_LIABILITIES = "aiWealthLiabilities"

    // Expense categories — single source of truth for the entire app
    val CATEGORIES = listOf(
        "Food", "Transport", "Shopping", "Groceries", "Bills",
        "Healthcare", "Entertainment", "Education",
        "Housing", "Investment",
        "Cash", "Transfer", "Income",
        "Refund", "Cashback", "Other Income",
        "Uncategorized",
        "Other"
    )
}
