package com.example.team_arthsetu.bankstatement

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.team_arthsetu.utils.CategoryResolver
import com.example.team_arthsetu.utils.Constants
import java.io.File
import java.io.FileOutputStream
import com.example.team_arthsetu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

object BankStatementImport {

    /** Bank statement import accepts PDF only; text is read with PDFBox (embedded text). */
    enum class FileKind { PDF, UNSUPPORTED }

    fun detectKind(mimeType: String?, displayName: String?): FileKind {
        val m = mimeType?.lowercase() ?: ""
        val n = displayName?.lowercase() ?: ""
        return when {
            m == "application/pdf" || n.endsWith(".pdf") -> FileKind.PDF
            else -> FileKind.UNSUPPORTED
        }
    }

    fun copyUriToCache(context: Context, uri: Uri): File {
        val ext = guessExtension(context, uri)
        val dest = File(context.cacheDir, "bank_stmt_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open file")
        return dest
    }

    private fun guessExtension(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
        val fromMime = when {
            mime == "application/pdf" -> "pdf"
            else -> null
        }
        if (fromMime != null) return fromMime
        val path = uri.lastPathSegment ?: return "pdf"
        MimeTypeMap.getFileExtensionFromUrl(path)?.lowercase()?.takeIf { it.isNotBlank() }?.let { return it }
        return if (path.endsWith(".pdf", true)) "pdf" else "pdf"
    }

    suspend fun extractRawText(context: Context, file: File, kind: FileKind): String =
        withContext(Dispatchers.IO) {
            when (kind) {
                FileKind.PDF -> BankStatementPdfExtractor.extractText(context, file)
                FileKind.UNSUPPORTED -> throw IllegalArgumentException("Unsupported file type")
            }
        }

    fun parseTransactions(rawText: String): List<StatementTransaction> =
        StatementLineParser.parseAll(PdfTextNormalizer.normalize(rawText))

    suspend fun categorize(
        context: Context,
        rows: List<StatementTransaction>,
        onProgress: ((String) -> Unit)? = null
    ): List<StatementTransaction> {
        if (rows.isEmpty()) return rows
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            val out = ArrayList<StatementTransaction>(rows.size)
            val total = rows.size
            rows.forEachIndexed { index, t ->
                if (index == 0 || (index + 1) % 4 == 0 || index == total - 1) {
                    try {
                        onProgress?.invoke(
                            app.getString(R.string.bank_progress_categorize, index + 1, total)
                        )
                    } catch (_: Exception) { }
                }
                val txnType = when (t.type) {
                    StatementTransaction.TYPE_CREDIT -> Constants.CREDIT
                    else -> Constants.DEBIT
                }
                val bodyForCat = listOf(t.merchant, t.narration)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" ")
                val cat = CategoryResolver.resolve(app, t.merchant, bodyForCat, txnType)
                out.add(t.copy(category = cat))
                if (index % 2 == 0) yield()
            }
            out
        }
    }
}
