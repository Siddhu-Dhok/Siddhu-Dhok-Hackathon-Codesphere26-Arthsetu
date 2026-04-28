package com.example.team_arthsetu.bankstatement

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads bank statement PDFs using **PDFBox only** (embedded text).
 * Scanned PDFs without a text layer will yield little or no text — use a text-based export from your bank.
 */
object BankStatementPdfExtractor {

    private val initialized = AtomicBoolean(false)

    fun ensureLoaded(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    /**
     * Stripper tuned for statements: visual reading order (columns/rows) vs default stream order.
     */
    private fun createTextStripper(): PDFTextStripper =
        PDFTextStripper().apply {
            sortByPosition = true
            lineSeparator = "\n"
        }

    fun extractText(context: Context, file: File): String {
        ensureLoaded(context)
        return try {
            PDDocument.load(file).use { doc ->
                createTextStripper().getText(doc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDFBox extract failed", e)
            ""
        }
    }

    /** Load PDF from a stream (caller closes the stream after this returns). */
    fun extractText(context: Context, inputStream: InputStream): String {
        ensureLoaded(context)
        return try {
            PDDocument.load(inputStream).use { doc ->
                createTextStripper().getText(doc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDFBox stream extract failed", e)
            ""
        }
    }

    private const val TAG = "BankStmtPDF"
}
