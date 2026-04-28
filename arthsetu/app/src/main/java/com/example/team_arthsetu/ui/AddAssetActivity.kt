package com.example.team_arthsetu.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Asset
import com.example.team_arthsetu.repository.WealthRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AddAssetActivity : AppCompatActivity() {

    private val repository = WealthRepository()

    // Chip → (type_key, emoji, display_label)
    private val chipMap by lazy {
        mapOf(
            R.id.chipStocks     to Triple("stocks",        "📈", "Stocks"),
            R.id.chipMutualFund to Triple("mutual_fund",   "📊", "Mutual Fund"),
            R.id.chipGold       to Triple("gold",          "🥇", "Gold"),
            R.id.chipCrypto     to Triple("crypto",        "🪙", "Crypto"),
            R.id.chipRealEstate to Triple("real_estate",   "🏠", "Real Estate"),
            R.id.chipSavings    to Triple("savings",       "💰", "Savings"),
            R.id.chipFD         to Triple("fixed_deposit", "🏦", "Fixed Deposit"),
            R.id.chipOther      to Triple("other",         "💳", "Other")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_asset)

        val toolbar         = findViewById<MaterialToolbar>(R.id.toolbar)
        val chipGroup       = findViewById<ChipGroup>(R.id.chipGroupAssetType)
        val tvSelectedIcon  = findViewById<TextView>(R.id.tvSelectedIcon)
        val tvSelectedName  = findViewById<TextView>(R.id.tvSelectedTypeName)
        val etName          = findViewById<TextInputEditText>(R.id.etAssetName)
        val etNote          = findViewById<TextInputEditText>(R.id.etAssetNote)
        val etValue         = findViewById<TextInputEditText>(R.id.etAssetValue)
        val etChange        = findViewById<TextInputEditText>(R.id.etAssetChange)
        val etSip           = findViewById<TextInputEditText>(R.id.etAssetSip)
        val btnSave         = findViewById<MaterialButton>(R.id.btnSaveAsset)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Update icon + label live as chip changes
        chipGroup.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val (_, emoji, label) = chipMap[id] ?: return@setOnCheckedStateChangeListener
            tvSelectedIcon.text = emoji
            tvSelectedName.text = label
        }

        btnSave.setOnClickListener {
            val name   = etName.text?.toString()?.trim() ?: ""
            val note   = etNote.text?.toString()?.trim() ?: ""
            val value  = etValue.text?.toString()?.toDoubleOrNull() ?: 0.0
            val change = etChange.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sip    = etSip.text?.toString()?.toDoubleOrNull() ?: 0.0
            val typeKey = chipMap[chipGroup.checkedChipId]?.first ?: "other"

            if (name.isBlank()) {
                etName.error = "Please enter asset name"
                etName.requestFocus()
                return@setOnClickListener
            }
            if (value <= 0) {
                etValue.error = "Please enter a value greater than 0"
                etValue.requestFocus()
                return@setOnClickListener
            }

            // Guard: must be signed in
            if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
                AppSnackbar.showLong(btnSave, "You must be logged in to save data.")
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            btnSave.text = "Saving…"

            lifecycleScope.launch {
                try {
                    repository.addAsset(
                        Asset(
                            name = name,
                            type = typeKey,
                            value = value,
                            note = note,
                            changePercent = change,
                            monthlyInvestment = sip
                        )
                    )
                    setResult(Activity.RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    btnSave.isEnabled = true
                    btnSave.text = "Save Asset"
                    val msg = when {
                        e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
                        e.message?.contains("permission", ignoreCase = true) == true ->
                            "Permission denied. Please update your Firestore Security Rules in Firebase Console."
                        else -> "Save failed: ${e.message}"
                    }
                    AppSnackbar.showLong(btnSave, msg)
                }
            }
        }
    }
}
