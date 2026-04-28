package com.example.team_arthsetu.repository

import android.content.Context
import android.net.Uri
import com.example.team_arthsetu.R
import com.example.team_arthsetu.bankstatement.BankStatementImport
import com.example.team_arthsetu.bankstatement.PdfTransactionParser
import com.example.team_arthsetu.bankstatement.StatementTransaction
import com.example.team_arthsetu.utils.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF bank statement import: copy URI → parse with [PdfTransactionParser] on a worker thread.
 */
class BankStatementRepository {

    suspend fun processPdfUri(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        onProgress: ((String) -> Unit)? = null
    ): PdfTransactionParser.Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        fun p(msg: String) {
            try {
                onProgress?.invoke(msg)
            } catch (_: Exception) { }
        }

        val kind = BankStatementImport.detectKind(mimeType, displayName)
        if (kind != BankStatementImport.FileKind.PDF) {
            return@withContext PdfTransactionParser.Result.Failure(
                "Only PDF bank statements are supported."
            )
        }
        p(app.getString(R.string.bank_progress_copying))
        val file: File = try {
            BankStatementImport.copyUriToCache(app, uri)
        } catch (e: Exception) {
            return@withContext PdfTransactionParser.Result.Failure("Could not read file.", e)
        }
        try {
            PdfTransactionParser.parseFile(app, file, onProgress)
        } finally {
            try {
                file.delete()
            } catch (_: Exception) { }
        }
    }

    suspend fun loadSmsTransactions(): List<com.example.team_arthsetu.model.Transaction> =
        withContext(Dispatchers.IO) {
            FirestoreRepository().getTransactions()
        }

    /** Inbox bank SMS with raw body (for ref matching and “not yet in expenses” rows). */
    suspend fun readInboxBankSmsPairs(context: Context): List<Pair<com.example.team_arthsetu.model.Transaction, String>> =
        withContext(Dispatchers.IO) {
            try {
                SmsParser.readBankSms(context.applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun categorizeRows(
        context: Context,
        rows: List<StatementTransaction>,
        onProgress: ((String) -> Unit)? = null
    ): List<StatementTransaction> =
        BankStatementImport.categorize(context.applicationContext, rows, onProgress)
}
