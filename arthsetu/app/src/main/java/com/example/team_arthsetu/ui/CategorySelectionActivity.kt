package com.example.team_arthsetu.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CategorySelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_selection)

        val transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
            ?: intent.getStringExtra(EXTRA_TXN_ID)
            ?: run {
                finish()
                return
            }
        val merchant = intent.getStringExtra(EXTRA_MERCHANT).orEmpty()
        val txnType = intent.getStringExtra(EXTRA_TXN_TYPE) ?: Constants.DEBIT

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val tvMerchant = findViewById<TextView>(R.id.tvMerchant)
        val etCategory = findViewById<AutoCompleteTextView>(R.id.etCategory)
        val btnSubmit = findViewById<MaterialButton>(R.id.btnSubmitCategory)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        tvMerchant.text = merchant.ifBlank { "Unknown merchant" }

        lifecycleScope.launch {
            val options: List<String> = withContext(Dispatchers.IO) {
                val fromFirestore: List<String> = try {
                    FirestoreRepository().getAllMerchantCategories()
                } catch (_: Exception) {
                    emptyList()
                }
                (Constants.CATEGORIES + fromFirestore)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase(Locale.US) }
                    .sortedBy { it.lowercase(Locale.US) }
            }
            etCategory.setAdapter(
                ArrayAdapter(
                    this@CategorySelectionActivity,
                    R.layout.item_category_dropdown,
                    android.R.id.text1,
                    options
                )
            )
            etCategory.threshold = 0
            etCategory.setOnClickListener { etCategory.showDropDown() }
            etCategory.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) (v as AutoCompleteTextView).showDropDown()
            }
        }

        btnSubmit.setOnClickListener {
            val category = etCategory.text?.toString()?.trim().orEmpty()
            if (category.isBlank()) {
                AppSnackbar.show(btnSubmit, "Please select or enter a category")
                return@setOnClickListener
            }
            if (FirebaseAuth.getInstance().currentUser == null) {
                AppSnackbar.show(btnSubmit, "Sign in required")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val repo = FirestoreRepository()
                        repo.updateSingleTransaction(transactionId, category)
                        if (txnType.equals(Constants.DEBIT, ignoreCase = true)) {
                            repo.saveMerchantMapping(merchant, category)
                            repo.updateCategoryForAllTransactions(merchant, category)
                        }
                    }
                    if (txnType.equals(Constants.DEBIT, ignoreCase = true)) {
                        MerchantCategoryStore.setCategory(applicationContext, merchant, category)
                    }
                    finish()
                } catch (e: Exception) {
                    AppSnackbar.showLong(btnSubmit, e.message ?: "Save failed")
                }
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "transactionId"
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_MERCHANT = "merchant"
        const val EXTRA_TXN_TYPE = "txn_type"
    }
}
