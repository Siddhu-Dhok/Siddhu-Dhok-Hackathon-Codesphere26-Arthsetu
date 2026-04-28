package com.example.team_arthsetu.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.MerchantCategoryStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shown from transaction notifications or FCM when the user should assign a category.
 * Persists to Firestore [Constants.SUB_MERCHANT_CATEGORIES] and local [MerchantCategoryStore].
 */
class MerchantCategoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merchant_category)

        val txnId = intent.getStringExtra(EXTRA_TXN_ID) ?: run {
            finish()
            return
        }
        val merchant = intent.getStringExtra(EXTRA_MERCHANT) ?: ""
        val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
        val txnType = intent.getStringExtra(EXTRA_TXN_TYPE) ?: "debit"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvMerchant = findViewById<TextView>(R.id.tvMerchant)
        val tvAmount = findViewById<TextView>(R.id.tvAmount)
        val spinner = findViewById<Spinner>(R.id.spinnerCategory)
        val etCustom = findViewById<TextInputEditText>(R.id.etCustomCategory)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveCategory)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvMerchant.text = merchant.ifBlank { "Unknown merchant" }
        val typeLabel = if (txnType == "credit") "Credit" else "Debit"
        tvAmount.text = String.format(Locale.US, "%s · ₹%.0f", typeLabel, amount)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Constants.CATEGORIES)
        spinner.adapter = adapter

        btnSave.setOnClickListener {
            val custom = etCustom.text?.toString()?.trim().orEmpty()
            val fromSpinner = spinner.selectedItem?.toString()?.trim().orEmpty()
            val category = custom.ifBlank { fromSpinner }
            if (category.isBlank()) {
                AppSnackbar.show(btnSave, "Choose or enter a category")
                return@setOnClickListener
            }
            if (FirebaseAuth.getInstance().currentUser == null) {
                AppSnackbar.show(btnSave, "Sign in required")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val repo = FirestoreRepository()
                        repo.updateTransactionCategory(txnId, category)
                        if (txnType.equals(Constants.DEBIT, ignoreCase = true)) {
                            repo.saveMerchantCategoryMapping(merchant, category)
                            repo.updateCategoryForAllTransactions(merchant, category)
                        }
                    }
                    if (txnType.equals(Constants.DEBIT, ignoreCase = true)) {
                        MerchantCategoryStore.setCategory(applicationContext, merchant, category)
                    }
                    finish()
                } catch (e: Exception) {
                    AppSnackbar.showLong(btnSave, e.message ?: "Save failed")
                }
            }
        }
    }

    companion object {
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_MERCHANT = "merchant"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_TXN_TYPE = "txn_type"
    }
}
