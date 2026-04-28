package com.example.team_arthsetu.utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic Firestore document id for transactions when no bank reference is present.
 * When SMS includes a ref (UTR / IMPS / UPI ref), [com.example.team_arthsetu.model.Transaction.smsFirestoreDocumentId]
 * uses `sms_ref_<digits>` instead for stronger dedupe across wording variants.
 */
object TransactionIdFactory {

    private const val PREFIX = "t"

    /** Collapses whitespace, trims, lowercases — stable across duplicate PDUs / spacing. */
    fun normalizeSmsBody(body: String): String =
        body.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)

    /**
     * Stable id: `t` + first 40 hex chars of SHA-256(
     *   normalizedMessage + "|" + amount + "|" + timestamp + "|" + merchant
     * ).
     */
    fun documentId(normalizedBody: String, amount: Double, timestamp: Long, merchant: String): String {
        val payload = buildString {
            append(normalizedBody)
            append('|')
            append(String.format(Locale.US, "%.2f", amount))
            append('|')
            append(timestamp)
            append('|')
            append(merchant.trim().lowercase(Locale.US))
        }
        val md = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
        val hex = md.joinToString("") { "%02x".format(it) }
        return PREFIX + hex.take(40)
    }

    /** Short stable key for WorkManager unique work (one job per logical SMS text). */
    fun workDedupeKey(normalizedBody: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(normalizedBody.toByteArray(StandardCharsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }.take(32)
    }
}
