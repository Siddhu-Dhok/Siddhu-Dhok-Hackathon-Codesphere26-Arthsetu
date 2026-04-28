package com.example.team_arthsetu.utils

import android.content.Context
import java.util.Locale

/**
 * Debit vs credit categorization:
 *
 * - **Debit:** learned mappings (elsewhere) → minimal built-ins (ZOMATO, UBER) → `Uncategorized`.
 * - **Credit:** simple income-style labels only — no learning hooks here.
 */
object CategoryClassifier {

    /** Matches [com.example.team_arthsetu.utils.TransactionParser] fallback merchant label. */
    const val UNKNOWN_MERCHANT_SENTINEL = "UNKNOWN"

    /** Normalize for rule checks: [merchant.trim] + uppercase (ASCII). */
    fun normalizeMerchant(merchant: String): String = merchant.trim().uppercase(Locale.US)

    /**
     * Persisted merchant on [Transaction] rows: uppercase for stable matching;
     * keeps [UNKNOWN_MERCHANT_SENTINEL] casing as-is.
     */
    fun normalizeMerchantForStorage(merchant: String): String {
        val t = merchant.trim()
        if (t.equals(UNKNOWN_MERCHANT_SENTINEL, ignoreCase = true)) return UNKNOWN_MERCHANT_SENTINEL
        return normalizeMerchant(merchant)
    }

    // ── Debit: minimal built-ins (after learned mapping misses) ───────────────

    /**
     * Built-in debit hints from merchant + optional statement line / SMS body.
     * Learned mappings are applied earlier in [CategoryResolver].
     */
    fun classifyDebitMinimalRules(merchant: String, statementOrBody: String = ""): String {
        val m = normalizeMerchant(merchant)
        val extra = statementOrBody.trim().uppercase(Locale.US).take(400)
        val hay = "$m $extra"
        if (hay.isBlank()) return "Uncategorized"

        fun hContains(s: String) = hay.contains(s)

        if (hContains("ZOMATO") || hContains("SWIGGY") || hContains("DOMINO") || hContains("MCDONALD") ||
            hContains("KFC") || hContains("PIZZA") || hContains("RESTAURANT") || hContains("DINEOUT") ||
            hContains("EATS") || hContains("FOOD")
        ) {
            return "Food"
        }
        if (hContains("UBER") || hContains("OLA") || hContains("RAPIDO") || hContains("IRCTC") ||
            hContains("REDBUS") || hContains("RAILWAY") || hContains("METRO") || hContains("PARKING") ||
            hContains("TOLL") || hContains("FASTAG")
        ) {
            return "Transport"
        }
        if (hContains("PETROL") || hContains("FUEL") || hContains("HPCL") || hContains("IOCL") ||
            hContains("BPCL") || hContains("SHELL") || hContains("INDIANOIL")
        ) {
            return "Fuel"
        }
        if (hContains("AMAZON") || hContains("FLIPKART") || hContains("MYNTRA") || hContains("NYKAA") ||
            hContains("DMART") || hContains("BIGBASKET") || hContains("RELIANCE") || hContains("MART")
        ) {
            return "Shopping"
        }
        if (hContains("NETFLIX") || hContains("SPOTIFY") || hContains("HOTSTAR") || hContains("PRIME") ||
            hContains("BOOKMYSHOW") || hContains("SONYLIV") || hContains("YOUTUBE")
        ) {
            return "Entertainment"
        }
        if (hContains("JIO") || hContains("AIRTEL") || hContains("VODAFONE") || hContains("VI ") ||
            hContains("RECHARGE") || hContains("ELECTRICITY") || hContains("BESCOM") ||
            Regex("""\bBILL(?:PAY|S)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(hay)
        ) {
            return "Bills & Utilities"
        }
        if (hContains("APOLLO") || hContains("PHARMACY") || hContains("NETMEDS") || hContains("1MG") ||
            hContains("HOSPITAL") || hContains("DIAGNOSTIC")
        ) {
            return "Healthcare"
        }
        return "Uncategorized"
    }

    /**
     * True when a **debit** row should appear in the “needs category” queue / FCM path.
     */
    fun needsDebitUserCategorization(merchant: String, category: String): Boolean {
        if (category.equals("Uncategorized", ignoreCase = true)) return true
        return merchant.trim().equals(UNKNOWN_MERCHANT_SENTINEL, ignoreCase = true)
    }

    // ── Credit: simple income buckets (no learning) ───────────────────────────

    private val reSalary = Regex("""\b(SALARY|PAYROLL|WAGES)\b""", RegexOption.IGNORE_CASE)
    private val reReversal = Regex("""\b(REVERSAL|REVERSED|REVERSE)\b""", RegexOption.IGNORE_CASE)
    private val reRefund = Regex("""\b(REFUND|CREDITED\s+BACK)\b""", RegexOption.IGNORE_CASE)
    private val reCashback = Regex("""\b(CASHBACK|CASH\s*BACK)\b""", RegexOption.IGNORE_CASE)

    /**
     * Priority: Cashback → Refund → Income → Other Income.
     */
    fun classifyCredit(merchant: String, body: String): String {
        val text = "${merchant.trim()} ${body.trim()}".trim()
        if (text.isBlank()) return "Other Income"
        if (reCashback.containsMatchIn(text)) return "Cashback"
        if (reReversal.containsMatchIn(text) || reRefund.containsMatchIn(text)) return "Refund"
        if (reSalary.containsMatchIn(text)) return "Income"
        return "Other Income"
    }

    /**
     * Legacy SMS inbox path without async resolver: same as [classifyDebitMinimalRules] for debit;
     * use [classifyCredit] when type is credit (see [SmsParser]).
     */
    fun classifyForSmsInbox(merchant: String, body: String): String =
        classifyDebitMinimalRules(merchant, body)

    /** @deprecated Prefer [classifyDebitMinimalRules] or resolver pipeline. */
    fun classifyStrict(merchant: String): String = classifyDebitMinimalRules(merchant)

    /** Local prefs + minimal debit rules (no Firestore). */
    fun classifyWithContext(context: Context, merchant: String, body: String): String {
        MerchantCategoryStore.getCategory(context, merchant)?.let { return it }
        return classifyDebitMinimalRules(merchant, body)
    }

    fun classify(merchant: String, body: String): String =
        if (body.isBlank()) classifyDebitMinimalRules(merchant) else classifyDebitMinimalRules(merchant, body)

    // ── Visual helpers ────────────────────────────────────────────────────────

    fun colorFor(category: String): Int = when (category) {
        "Food"          -> 0xFF00C070.toInt()
        "Transport"     -> 0xFF4C35DC.toInt()
        "Shopping"      -> 0xFFFF3B30.toInt()
        "Groceries"     -> 0xFF34C759.toInt()
        "Bills"         -> 0xFFFF9F0A.toInt()
        "Healthcare"    -> 0xFF00BCD4.toInt()
        "Entertainment" -> 0xFF9C27B0.toInt()
        "Education"     -> 0xFF3F51B5.toInt()
        "Housing"       -> 0xFF1A1A2E.toInt()
        "Investment"    -> 0xFF009688.toInt()
        "Cash"          -> 0xFF795548.toInt()
        "Transfer"      -> 0xFF2196F3.toInt()
        "Income"        -> 0xFF4CAF50.toInt()
        "Refund"        -> 0xFF26A69A.toInt()
        "Cashback"      -> 0xFFFFB74D.toInt()
        "Other Income"  -> 0xFF66BB6A.toInt()
        "Uncategorized" -> 0xFF8E8E93.toInt()
        "Fuel" -> 0xFFFF6D00.toInt()
        "Bills & Utilities" -> 0xFFFF9F0A.toInt()
        else            -> 0xFF8E8E93.toInt()
    }

    fun iconFor(category: String): String = when (category) {
        "Food"          -> "🍔"
        "Transport"     -> "🚗"
        "Shopping"      -> "🛍"
        "Groceries"     -> "🥦"
        "Bills"         -> "⚡"
        "Healthcare"    -> "💊"
        "Entertainment" -> "🎬"
        "Education"     -> "📚"
        "Housing"       -> "🏠"
        "Investment"    -> "📈"
        "Cash"          -> "🏧"
        "Transfer"      -> "↔️"
        "Income"        -> "💰"
        "Refund"        -> "↩️"
        "Cashback"      -> "🎁"
        "Other Income"  -> "💵"
        "Uncategorized" -> "❓"
        else            -> "💳"
    }
}
