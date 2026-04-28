package com.example.team_arthsetu.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.bankstatement.BankStatementSmsPdfMatcher
import com.example.team_arthsetu.bankstatement.PdfTransactionParser
import com.example.team_arthsetu.bankstatement.StatementTransaction
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.BankStatementRepository
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class BankStatementViewModel : ViewModel() {

    private val bankRepo = BankStatementRepository()
    private val firestoreRepo = FirestoreRepository()

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** User-visible step while [loading] is true (PDF import pipeline). */
    private val _loadingMessage = MutableLiveData<String>()
    val loadingMessage: LiveData<String> = _loadingMessage

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _fullPdfRows = MutableLiveData<List<StatementTransaction>>(emptyList())
    val fullPdfRows: LiveData<List<StatementTransaction>> = _fullPdfRows

    private val _smsMatchedFlags = MutableLiveData<List<Boolean>>(emptyList())
    val smsMatchedFlags: LiveData<List<Boolean>> = _smsMatchedFlags

    /** PDF row ref matches [Transaction.bankRefNo] on an SMS-sourced expense. */
    private val _pdfRowInExpenseBySmsRef = MutableLiveData<List<Boolean>>(emptyList())
    val pdfRowInExpenseBySmsRef: LiveData<List<Boolean>> = _pdfRowInExpenseBySmsRef

    private val _unmatchedSms = MutableLiveData<List<Transaction>>(emptyList())
    val unmatchedSms: LiveData<List<Transaction>> = _unmatchedSms

    /** Debit SMS in device inbox not yet present in Firestore expenses (same id as would be saved). */
    private val _inboxSmsNotInExpenses =
        MutableLiveData<List<Pair<Transaction, String>>>(emptyList())
    val inboxSmsNotInExpenses: LiveData<List<Pair<Transaction, String>>> = _inboxSmsNotInExpenses

    private val _usedOcrFallback = MutableLiveData(false)
    val usedOcrFallback: LiveData<Boolean> = _usedOcrFallback

    private val _saveMessage = MutableLiveData<String?>()
    val saveMessage: LiveData<String?> = _saveMessage

    fun processPdf(context: Context, uri: Uri, mimeType: String?, displayName: String?) {
        val appCtx = context.applicationContext
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _saveMessage.value = null
            _loadingMessage.value = appCtx.getString(R.string.bank_progress_copying)
            try {
                when (
                    val result = bankRepo.processPdfUri(
                        appCtx,
                        uri,
                        mimeType,
                        displayName,
                        onProgress = { msg -> _loadingMessage.postValue(msg) }
                    )
                ) {
                    is PdfTransactionParser.Result.Failure ->
                        _error.value = result.message

                    is PdfTransactionParser.Result.Success -> {
                        _usedOcrFallback.value = false
                        val categorized = bankRepo.categorizeRows(
                            appCtx,
                            result.statementTransactions,
                            onProgress = { msg -> _loadingMessage.postValue(msg) }
                        )
                        _fullPdfRows.value = categorized
                        _loadingMessage.postValue(appCtx.getString(R.string.bank_progress_sms))
                        applySmsReconciliation(appCtx, categorized)
                        _usedOcrFallback.value = result.usedOcrFallback
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
            } finally {
                _loading.value = false
            }
        }
    }

    fun saveStatementToCloud(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            _error.value = "Sign in to save to cloud."
            return
        }
        val rows = _fullPdfRows.value.orEmpty()
        if (rows.isEmpty()) return
        viewModelScope.launch {
            try {
                firestoreRepo.saveStatementTransactions(context.applicationContext, rows)
                _saveMessage.value = "Saved ${rows.size} transactions to Firestore."
            } catch (e: Exception) {
                _error.value = "Save failed: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun acknowledgeOcrFallbackNotice() {
        _usedOcrFallback.value = false
    }

    /** Call after saving an inbox SMS to expenses so lists and PDF match flags stay correct. */
    fun refreshSmsReconciliation(context: Context) {
        val rows = _fullPdfRows.value.orEmpty()
        if (rows.isEmpty()) return
        viewModelScope.launch {
            applySmsReconciliation(context.applicationContext, rows)
        }
    }

    private suspend fun applySmsReconciliation(
        appCtx: Context,
        categorized: List<StatementTransaction>
    ) {
        val sms = bankRepo.loadSmsTransactions()
        val inboxPairs = bankRepo.readInboxBankSmsPairs(appCtx)
        val smsIdToBody = inboxPairs.associate { it.first.id to it.second }
        val firestoreIds = sms.map { it.id }.toSet()
        val firestoreRefDigits = sms
            .map { it.bankRefNo.filter { ch -> ch.isDigit() } }
            .filter { it.isNotBlank() }
            .toSet()
        val inboxNotSaved = inboxPairs.filter { (t, _) ->
            if (!t.type.equals(Constants.DEBIT, ignoreCase = true)) return@filter false
            if (t.id in firestoreIds) return@filter false
            val inboxRef = t.bankRefNo.filter { ch -> ch.isDigit() }
            if (inboxRef.isNotBlank() && inboxRef in firestoreRefDigits) return@filter false
            true
        }
        _loadingMessage.postValue(appCtx.getString(R.string.bank_progress_matching))
        val smsExpenseRefDigits = sms
            .filter { it.source.equals(Constants.TXN_SOURCE_SMS, ignoreCase = true) }
            .map { txn -> txn.bankRefNo.filter { ch -> ch.isDigit() } }
            .filter { it.isNotBlank() }
            .toSet()
        val refInExpenseFlags = categorized.map { pdf ->
            val r = pdf.refNo.filter { ch -> ch.isDigit() }
            r.isNotBlank() && r in smsExpenseRefDigits
        }
        val unmatched = BankStatementSmsPdfMatcher.unmatchedSmsDebits(sms, categorized, smsIdToBody)
        val flags = categorized.map { pdf ->
            BankStatementSmsPdfMatcher.pdfRowMatchedSms(pdf, sms, smsIdToBody)
        }
        _smsMatchedFlags.postValue(flags)
        _pdfRowInExpenseBySmsRef.postValue(refInExpenseFlags)
        _unmatchedSms.postValue(unmatched)
        _inboxSmsNotInExpenses.postValue(inboxNotSaved)
    }
}
