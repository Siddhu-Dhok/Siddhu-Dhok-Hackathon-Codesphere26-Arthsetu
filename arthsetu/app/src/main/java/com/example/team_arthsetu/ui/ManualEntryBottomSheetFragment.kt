package com.example.team_arthsetu.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Transaction
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.CategoryClassifier
import com.example.team_arthsetu.utils.Constants
import com.example.team_arthsetu.utils.MerchantCategoryStore
import com.example.team_arthsetu.utils.MerchantNormalizer
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManualEntryBottomSheetFragment : BottomSheetDialogFragment() {

    private val viewModel: ExpenseViewModel by activityViewModels()
    private val repository = FirestoreRepository()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_add_expense, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etMerchant = view.findViewById<TextInputEditText>(R.id.etMerchant)
        val spinnerCategory = view.findViewById<AutoCompleteTextView>(R.id.spinnerCategory)
        val actvStoredMerchant = view.findViewById<AutoCompleteTextView>(R.id.actvStoredMerchant)
        val rgMerchantMode = view.findViewById<RadioGroup>(R.id.rgMerchantMode)
        val rbStored = view.findViewById<View>(R.id.rbStoredMerchant)
        val toggleType = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleType)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val tilStored = view.findViewById<TextInputLayout>(R.id.tilStoredMerchant)
        val tilNew = view.findViewById<TextInputLayout>(R.id.tilNewMerchant)
        val layoutCategorySection = view.findViewById<LinearLayout>(R.id.layoutCategorySection)
        val tilCategory = view.findViewById<TextInputLayout>(R.id.tilCategory)

        toggleType.check(R.id.btnDebit)

        lifecycleScope.launch {
            val merchants = withContext(Dispatchers.IO) {
                try {
                    repository.getAllMappedMerchants()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            actvStoredMerchant.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, merchants)
            )
            actvStoredMerchant.threshold = 0
            actvStoredMerchant.setOnClickListener { actvStoredMerchant.showDropDown() }
        }

        lifecycleScope.launch {
            val categories = loadCategoryChoicesForNewMerchant()
            spinnerCategory.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, categories)
            )
            spinnerCategory.threshold = 0
            spinnerCategory.setOnClickListener { spinnerCategory.showDropDown() }
            if (categories.isNotEmpty()) {
                spinnerCategory.setText(categories[0], false)
            }
        }

        fun applyMerchantModeUi(storedMode: Boolean) {
            tilStored.visibility = if (storedMode) View.VISIBLE else View.GONE
            tilNew.visibility = if (storedMode) View.GONE else View.VISIBLE
            layoutCategorySection.visibility = if (storedMode) View.GONE else View.VISIBLE
        }

        rgMerchantMode.setOnCheckedChangeListener { _, checkedId ->
            applyMerchantModeUi(checkedId == R.id.rbStoredMerchant)
        }
        rbStored.performClick()

        btnSave.setOnClickListener {
            val amtStr = etAmount.text?.toString()?.trim().orEmpty()
            val amount = amtStr.toDoubleOrNull()
            val storedMode = rgMerchantMode.checkedRadioButtonId == R.id.rbStoredMerchant
            val rawMerchant = if (storedMode) {
                actvStoredMerchant.text?.toString()?.trim().orEmpty()
            } else {
                etMerchant.text?.toString()?.trim().orEmpty()
            }
            val merchant = rawMerchant.trim().uppercase(Locale.US)
            val type = if (toggleType.checkedButtonId == R.id.btnDebit) "debit" else "credit"

            if (amount == null || amount <= 0) {
                etAmount.error = "Enter a valid amount"
                return@setOnClickListener
            }
            if (merchant.isBlank()) {
                if (storedMode) {
                    actvStoredMerchant.error = "Select merchant"
                } else {
                    etMerchant.error = "Enter merchant"
                }
                return@setOnClickListener
            }
            if (!storedMode) {
                val category = spinnerCategory.text?.toString()?.trim().orEmpty()
                if (category.isBlank()) {
                    tilCategory.error = "Select category"
                    return@setOnClickListener
                }
                tilCategory.error = null
            }

            lifecycleScope.launch {
                val category = if (storedMode) {
                    val resolved = withContext(Dispatchers.IO) {
                        resolveCategoryForStoredMerchant(merchant)
                    }
                    if (resolved.isNullOrBlank()) {
                        AppSnackbar.showLong(
                            view,
                            "No saved category for this merchant yet. Categorize a transaction for this merchant first, or use New merchant."
                        )
                        return@launch
                    }
                    resolved
                } else {
                    spinnerCategory.text?.toString()?.trim().orEmpty()
                }

                val mStored = CategoryClassifier.normalizeMerchantForStorage(merchant)
                val txn = Transaction(
                    id = "",
                    amount = amount,
                    type = type,
                    merchant = mStored,
                    merchantKey = MerchantNormalizer.looseKey(mStored),
                    category = category,
                    timestamp = System.currentTimeMillis()
                )
                viewModel.addTransaction(txn, requireContext()) { dismissAllowingStateLoss() }
            }
        }
    }

    /** Firestore `merchant_categories` categories + app defaults, de-duped. */
    private suspend fun loadCategoryChoicesForNewMerchant(): List<String> = withContext(Dispatchers.IO) {
        val fromServer = runCatching { repository.getAllMerchantCategories() }.getOrDefault(emptyList())
        (fromServer + Constants.CATEGORIES)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedBy { it.uppercase(Locale.US) }
    }

    private suspend fun resolveCategoryForStoredMerchant(merchantUpper: String): String? {
        MerchantCategoryStore.getCategory(requireContext(), merchantUpper)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return repository.findLearnedMerchantCategory(merchantUpper)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    companion object {
        const val TAG = "ManualEntryBottomSheet"
    }
}
