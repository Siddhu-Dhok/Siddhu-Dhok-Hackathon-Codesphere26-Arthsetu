package com.example.team_arthsetu.bankstatement

import android.content.Context
import android.util.Log
import com.example.team_arthsetu.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlin.math.min

/**
 * PDF → plain text (PDFBox, optional OCR fallback) → [PdfParsedTransaction] / [StatementTransaction].
 * Testable entry points: [parseFile], [parseNormalizedText] (unit tests without PDF I/O).
 */
object PdfTransactionParser {

    private const val TAG = "PdfTxnParser"
    private const val MIN_MEANINGFUL_CHARS = 80
    private const val MAX_OCR_PAGES = 15

    sealed class Result {
        data class Success(
            val statementTransactions: List<StatementTransaction>,
            val parsedTransactions: List<PdfParsedTransaction>,
            val rawText: String,
            val normalizedText: String,
            val usedOcrFallback: Boolean
        ) : Result()

        data class Failure(val message: String, val cause: Throwable? = null) : Result()
    }

    fun parseNormalizedText(normalized: String): List<StatementTransaction> =
        StatementLineParser.parseAll(normalized)

    /**
     * Full pipeline: [file] must be readable for OCR pages when text layer is empty.
     * [onProgress] receives user-visible status lines (call from background thread is OK).
     */
    fun parseFile(context: Context, file: File, onProgress: ((String) -> Unit)? = null): Result {
        val app = context.applicationContext
        fun p(msg: String) {
            try {
                onProgress?.invoke(msg)
            } catch (_: Exception) { }
        }

        p(app.getString(R.string.bank_progress_extracting))
        val rawEmbedded = try {
            BankStatementPdfExtractor.extractText(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "embedded text failed", e)
            ""
        }
        Log.d(TAG, "Embedded PDF text chars=${rawEmbedded.length}, meaningful=${meaningfulCount(rawEmbedded)}")

        var raw = rawEmbedded
        var usedOcr = false
        if (meaningfulCount(raw) < MIN_MEANINGFUL_CHARS) {
            val ocrText = ocrPdfPages(context, file, onProgress)
            if (meaningfulCount(ocrText) > meaningfulCount(raw)) {
                raw = ocrText
                usedOcr = true
                Log.d(TAG, "OCR fallback used; meaningful=${meaningfulCount(raw)}")
            }
        }

        if (raw.isBlank()) {
            return Result.Failure(
                "No readable text in this PDF. It may be scanned or encrypted—try a text-based export from your bank."
            )
        }

        p(app.getString(R.string.bank_progress_parsing))
        val normalized = PdfTextNormalizer.normalize(raw)
        Log.d(TAG, "Normalized preview (2000 chars):\n${normalized.take(2000)}")

        val rows = parseNormalizedText(normalized)
        val parsed = rows.map { PdfParsedTransaction.fromStatement(it) }

        if (rows.isEmpty()) {
            return Result.Failure(
                "No transaction rows could be parsed. Check that dates and amounts match supported patterns.",
                null
            )
        }

        return Result.Success(
            statementTransactions = rows,
            parsedTransactions = parsed,
            rawText = raw,
            normalizedText = normalized,
            usedOcrFallback = usedOcr
        )
    }

    private fun meaningfulCount(s: String): Int = s.count { it.isLetterOrDigit() }

    private fun ocrPdfPages(context: Context, file: File, onProgress: ((String) -> Unit)?): String {
        val app = context.applicationContext
        fun p(msg: String) {
            try {
                onProgress?.invoke(msg)
            } catch (_: Exception) { }
        }
        val pageCount = try {
            PDDocument.load(file).use { it.numberOfPages }
        } catch (e: Exception) {
            Log.w(TAG, "page count", e)
            1
        }
        val pagesToScan = min(pageCount.coerceAtLeast(1), MAX_OCR_PAGES)
        val sb = StringBuilder()
        for (i in 0 until pagesToScan) {
            p(app.getString(R.string.bank_progress_ocr, i + 1, pagesToScan))
            val bmp = BankStatementPdfPageRenderer.renderPageForOcr(file, i) ?: continue
            try {
                val pageText = BankStatementOcrExtractor.extractTextFromBitmapBlocking(bmp)
                if (pageText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(pageText)
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        return sb.toString()
    }
}
