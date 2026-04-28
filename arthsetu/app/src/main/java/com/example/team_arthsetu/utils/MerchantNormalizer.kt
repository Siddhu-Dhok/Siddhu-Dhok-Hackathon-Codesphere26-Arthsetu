package com.example.team_arthsetu.utils

import java.security.MessageDigest
import java.util.Locale

/**
 * Merchant normalization:
 *
 * - **[looseKey]** — letters only from preprocessed name (primary **identity** for mappings / [Transaction.merchantKey]).
 * - **[strictKey]** — letters only (**fallback** substring matching when lengths are safe).
 * - **[normalize]** — uppercase, punctuation → spaces (legacy token / display-style).
 */
object MerchantNormalizer {

    /** Minimum length before strict-key substring / fuzzy matching is allowed. */
    const val FUZZY_MERCHANT_MIN_LEN = 6

    private val monthTokens = setOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC"
    )
    private val noiseTokens = setOf(
        "ON", "AT", "TXN", "TRX", "UPI", "PAYMENT", "PAY", "PAID",
        "REF", "NO", "ID", "A", "AC", "ACC", "ACCOUNT", "FROM", "TO", "VIA"
    )
    private const val SIMILARITY_MATCH_THRESHOLD = 0.70

    /** Embedded date fragments without spaces, e.g. `29MAR26`, `29MAR`, `9MAR26`. */
    private val embeddedDateRegex = Regex("\\d{1,2}[A-Z]{3}\\d{0,2}", RegexOption.IGNORE_CASE)

    private val digitsRegex = Regex("\\d+")

    /**
     * Split glued "...NAMEONNEXT..." / "...NAMEATNEXT...".
     * Must NOT use variable-length lookbehind `(?<=[A-Z]{2,})` — Android ICU throws
     * [PatternSyntaxException] ("bounded maximum length"). Use capturing groups only.
     */
    private val gluedOnAtSplitRegex = Regex("([A-Z]{2,120})(ON|AT)([A-Z]{2,120})")

    /**
     * Run before splitting/tokenizing so concatenated SMS like `AJINKYAJAGANNAON29MAR26`
     * becomes comparable to `AJINKYAJAGANNA`.
     *
     * Order: uppercase → strip embedded dates → strip digits → strip noise → spaces.
     */
    private fun preprocessMerchantString(raw: String): String {
        var s = raw.trim().uppercase(Locale.US)
        s = s.replace(embeddedDateRegex, "")
        s = s.replace(digitsRegex, "")
        // Noise tokens (standalone / trailing / leading on glued strings)
        val trailingNoise = listOf("PAYMENT", "TXN", "UPI", "ON", "AT")
        for (noise in trailingNoise) {
            s = s.replace(Regex("${Regex.escape(noise)}$"), "")
            s = s.replace(Regex("^${Regex.escape(noise)}"), "")
        }
        s = s.replace(Regex("TXN|UPI|PAYMENT", RegexOption.IGNORE_CASE), " ")
        s = s.replace(gluedOnAtSplitRegex) { m ->
            "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}"
        }
        s = s.replace(Regex("[^A-Z]+"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun cleanedWords(merchant: String): List<String> {
        val pre = preprocessMerchantString(merchant)
        if (pre.isBlank() && merchant.any { it.isLetter() }) {
            val fallback = merchant.trim().uppercase(Locale.US)
                .replace(embeddedDateRegex, "")
                .replace(digitsRegex, "")
                .replace(Regex("[^A-Z]+"), "")
                .trim()
            if (fallback.isNotBlank()) return listOf(fallback)
            return emptyList()
        }
        val upper = pre
            .replace(Regex("[^A-Z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (upper.isBlank()) return emptyList()
        val raw = upper.split(' ').filter { it.isNotBlank() }
        val filtered = raw.filter { token ->
            when {
                token.all(Char::isDigit) -> false
                token in monthTokens -> false
                token in noiseTokens -> false
                token.length == 2 && token.all(Char::isDigit) -> false
                else -> true
            }
        }.toMutableList()

        // Common SMS tail noise: "... C ON 13 MAR 26 B" => drop trailing single-letter suffix.
        if (filtered.size > 2 && filtered.last().length == 1) {
            filtered.removeAt(filtered.lastIndex)
        }
        if (filtered.isNotEmpty()) return filtered

        // Prevent over-cleaning fallback: keep meaningful alphabetic words if token stripping got too aggressive.
        return raw.filter { it.any(Char::isLetter) && it.length >= 2 }
    }

    fun normalize(merchant: String): String {
        return cleanedWords(merchant).joinToString(" ").trim()
    }

    /** Letters only (A–Z), uppercase. */
    fun strictKey(merchant: String): String {
        return normalize(merchant).replace(Regex("[^A-Z]"), "")
    }

    /** Letters + digits (A–Z, 0–9), uppercase — canonical identity key. */
    fun looseKey(merchant: String): String {
        return normalize(merchant).replace(Regex("[^A-Z]"), "")
    }

    /** @deprecated Use [strictKey] for fallback matching or [looseKey] for identity. */
    fun mappingKey(merchant: String): String = strictKey(merchant)

    /**
     * Identity match (loose key) or safe strict-key fallback (substring only when both strict keys
     * are at least [FUZZY_MERCHANT_MIN_LEN]).
     */
    fun merchantsMatchForCategory(a: String, b: String): Boolean {
        val la = looseKey(a)
        val lb = looseKey(b)
        if (la.isNotBlank() && la == lb) return true

        val sa = strictKey(a)
        val sb = strictKey(b)
        if (sa.isBlank() || sb.isBlank()) return false
        // Exact match should always work, including short merchants like OLA/UBER.
        if (sa == sb) return true
        if (sa.length < FUZZY_MERCHANT_MIN_LEN || sb.length < FUZZY_MERCHANT_MIN_LEN) return false

        val containsMatch = sa.contains(sb) || sb.contains(sa)
        if (containsMatch) return true
        return normalizedSimilarity(normalize(a), normalize(b)) >= SIMILARITY_MATCH_THRESHOLD
    }

    /** @deprecated Prefer [merchantsMatchForCategory]. */
    fun mappingKeysMatch(a: String, b: String): Boolean = merchantsMatchForCategory(a, b)

    /** Strict-key score; fuzzy substring only when both strict keys are long enough. */
    fun strictKeyMatchScore(incomingRaw: String, storedMerchantField: String): Int {
        val a = strictKey(incomingRaw)
        val b = strictKey(storedMerchantField)
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 15_000
        if (a.length < FUZZY_MERCHANT_MIN_LEN || b.length < FUZZY_MERCHANT_MIN_LEN) return 0
        if (a.contains(b) || b.contains(a)) return 12_000 + minOf(a.length, b.length)
        val sim = normalizedSimilarity(normalize(incomingRaw), normalize(storedMerchantField))
        if (sim >= SIMILARITY_MATCH_THRESHOLD) return (11_000 + (sim * 1000).toInt())
        return 0
    }

    /** @deprecated Use [strictKeyMatchScore]. */
    fun mappingKeyMatchScore(incomingRaw: String, storedMerchantField: String): Int =
        strictKeyMatchScore(incomingRaw, storedMerchantField)

    fun firestoreDocumentId(normalizedUppercase: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(normalizedUppercase.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(64)
    }

    fun partialMatchScore(incomingNormalized: String, storedNormalized: String): Int {
        if (incomingNormalized.isBlank() || storedNormalized.isBlank()) return 0
        val a = incomingNormalized.trim()
        val b = storedNormalized.trim()
        if (a == b) return 10_000

        if (a.contains(b) || b.contains(a)) {
            if (a != b && minOf(a.length, b.length) < FUZZY_MERCHANT_MIN_LEN) return 0
            return 1000 + minOf(a.length, b.length)
        }
        var prefix = 0
        val n = minOf(a.length, b.length)
        while (prefix < n && a[prefix] == b[prefix]) prefix++
        if (prefix >= FUZZY_MERCHANT_MIN_LEN) return 800 + prefix

        for (tok in b.split(' ').filter { it.length >= FUZZY_MERCHANT_MIN_LEN }) {
            if (a.contains(tok)) return 600 + tok.length
        }
        for (tok in a.split(' ').filter { it.length >= FUZZY_MERCHANT_MIN_LEN }) {
            if (b.contains(tok)) return 600 + tok.length
        }

        val tokensA = a.split(' ').filter { it.length >= FUZZY_MERCHANT_MIN_LEN }.toSet()
        val tokensB = b.split(' ').filter { it.length >= FUZZY_MERCHANT_MIN_LEN }.toSet()
        val overlap = tokensA.intersect(tokensB).sumOf { it.length }
        return if (overlap > 0) overlap else 0
    }

    private fun normalizedSimilarity(aNorm: String, bNorm: String): Double {
        val a = aNorm.trim()
        val b = bNorm.trim()
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val ta = a.split(' ').filter { it.length >= 2 }.toSet()
        val tb = b.split(' ').filter { it.length >= 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }
}
