package com.example.team_arthsetu.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Liability
import com.example.team_arthsetu.repository.WealthRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AddLiabilityActivity : AppCompatActivity() {

    private val repository = WealthRepository()

    private val chipMap by lazy {
        mapOf(
            R.id.chipHomeLoan     to Triple("home_loan",      "🏠", "Home Loan"),
            R.id.chipCarLoan      to Triple("car_loan",       "🚗", "Car Loan"),
            R.id.chipPersonalLoan to Triple("personal_loan",  "👤", "Personal Loan"),
            R.id.chipCreditCard   to Triple("credit_card",    "💳", "Credit Card"),
            R.id.chipEduLoan      to Triple("education_loan", "🎓", "Education Loan"),
            R.id.chipOther        to Triple("other",          "📄", "Other")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_liability)

        val toolbar        = findViewById<MaterialToolbar>(R.id.toolbar)
        val chipGroup      = findViewById<ChipGroup>(R.id.chipGroupLiabilityType)
        val tvSelectedIcon = findViewById<TextView>(R.id.tvSelectedIcon)
        val tvSelectedName = findViewById<TextView>(R.id.tvSelectedTypeName)
        val etName         = findViewById<TextInputEditText>(R.id.etLiabilityName)
        val etAmount       = findViewById<TextInputEditText>(R.id.etLiabilityAmount)
        val etBank         = findViewById<TextInputEditText>(R.id.etLiabilityBank)
        val etInterestRate = findViewById<TextInputEditText>(R.id.etInterestRate)
        val etEmiDate      = findViewById<TextInputEditText>(R.id.etLiabilityEmiDate)
        val btnSave        = findViewById<MaterialButton>(R.id.btnSaveLiability)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        chipGroup.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val (_, emoji, label) = chipMap[id] ?: return@setOnCheckedStateChangeListener
            tvSelectedIcon.text = emoji
            tvSelectedName.text = label
        }

        btnSave.setOnClickListener {
            val name         = etName.text?.toString()?.trim() ?: ""
            val amount       = etAmount.text?.toString()?.toDoubleOrNull() ?: 0.0
            val bank         = etBank.text?.toString()?.trim() ?: ""
            val interestRate = etInterestRate.text?.toString()?.toDoubleOrNull() ?: 0.0
            val emiDate      = etEmiDate.text?.toString()?.trim() ?: ""
            val typeKey      = chipMap[chipGroup.checkedChipId]?.first ?: "other"

            if (name.isBlank()) {
                etName.error = "Please enter liability name"
                etName.requestFocus()
                return@setOnClickListener
            }
            if (amount <= 0) {
                etAmount.error = "Please enter amount greater than 0"
                etAmount.requestFocus()
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
                    repository.addLiability(
                        Liability(
                            name = name,
                            type = typeKey,
                            amount = amount,
                            bank = bank,
                            interestRate = interestRate,
                            nextEmiDate = emiDate
                        )
                    )
                    setResult(Activity.RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    btnSave.isEnabled = true
                    btnSave.text = "Save Liability"
                    val msg = when {
                        e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
                        e.message?.contains("permission", ignoreCase = true) == true ->
                            "Permission denied. Please update Firestore Security Rules in Firebase Console."
                        else -> "Save failed: ${e.message}"
                    }
                    AppSnackbar.showLong(btnSave, msg)
                }
            }
        }
    }
}
