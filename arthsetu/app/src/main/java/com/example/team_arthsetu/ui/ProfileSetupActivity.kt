package com.example.team_arthsetu.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.User
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.ProfilePhotoUtils
import com.example.team_arthsetu.utils.ProfileStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ProfileSetupActivity : AppCompatActivity() {

    private val repository = FirestoreRepository()
    private var localPhotoPath = ""

    private val employmentOptions = listOf(
        "Salaried Professional", "Self Employed", "Business Owner",
        "Student", "Retired", "Other"
    )

    // Pick photo from gallery, copy then normalize EXIF orientation + save as JPEG
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val file = File(filesDir, "profile_photo.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: run {
                AppSnackbar.show(findViewById(R.id.btnSaveProfile), "Could not load photo.")
                return@registerForActivityResult
            }
            val oriented = ProfilePhotoUtils.loadOrientedBitmap(file.absolutePath)
            if (oriented == null) {
                AppSnackbar.show(findViewById(R.id.btnSaveProfile), "Could not load photo.")
                return@registerForActivityResult
            }
            ProfilePhotoUtils.saveAsNormalizedJpeg(oriented, file)
            oriented.recycle()
            localPhotoPath = file.absolutePath
            displayPhoto(file.absolutePath)
            updateRotatePhotoVisibility()
        } catch (e: Exception) {
            AppSnackbar.show(findViewById(R.id.btnSaveProfile), "Could not load photo.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        val toolbar          = findViewById<MaterialToolbar>(R.id.toolbar)
        val layoutPhoto      = findViewById<FrameLayout>(R.id.layoutPhoto)
        val ivPhoto          = findViewById<ImageView>(R.id.ivPhoto)
        val tvInitials       = findViewById<TextView>(R.id.tvInitials)
        val etFullName       = findViewById<TextInputEditText>(R.id.etFullName)
        val etUsername       = findViewById<TextInputEditText>(R.id.etUsername)
        val etEmail          = findViewById<TextInputEditText>(R.id.etEmail)
        val tvPhone          = findViewById<TextView>(R.id.tvPhone)
        val layoutVerified   = findViewById<LinearLayout>(R.id.layoutVerified)
        val etIncome         = findViewById<TextInputEditText>(R.id.etMonthlyIncome)
        val spinnerEmploy    = findViewById<AutoCompleteTextView>(R.id.spinnerEmployment)
        val seekRisk         = findViewById<SeekBar>(R.id.seekRisk)
        val tvRiskLabel      = findViewById<TextView>(R.id.tvRiskLabel)
        val btnSave          = findViewById<MaterialButton>(R.id.btnSaveProfile)
        val btnRotatePhoto   = findViewById<MaterialButton>(R.id.btnRotatePhoto)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Employment dropdown
        spinnerEmploy.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, employmentOptions)
        )

        // Pre-fill from local cache
        val cached = ProfileStore.load(this)
        etFullName.setText(cached.fullName)
        etUsername.setText(cached.username)
        etEmail.setText(cached.email)

        if (cached.monthlyIncome > 0)
            etIncome.setText(cached.monthlyIncome.toInt().toString())
        if (cached.employmentType.isNotBlank())
            spinnerEmploy.setText(cached.employmentType, false)
        seekRisk.progress = cached.riskPreference
        tvRiskLabel.text  = riskLabel(cached.riskPreference)

        if (cached.photoPath.isNotBlank()) {
            localPhotoPath = cached.photoPath
            displayPhoto(cached.photoPath)
        }
        updateRotatePhotoVisibility()

        // Pre-fill phone from Firebase Auth (it's already verified via OTP)
        val authPhone = FirebaseAuth.getInstance().currentUser?.phoneNumber ?: ""
        val displayPhone = authPhone.removePrefix("+91").trim()
        tvPhone.text = displayPhone.ifBlank { cached.phone }
        if (authPhone.isNotBlank()) layoutVerified.visibility = android.view.View.VISIBLE

        // Update initials when name changes
        etFullName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                if (localPhotoPath.isBlank()) {
                    tvInitials.text = initials(s?.toString() ?: "")
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Risk slider
        seekRisk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                tvRiskLabel.text = riskLabel(p)
                val color = riskColor(p)
                tvRiskLabel.setTextColor(color)
                sb?.progressTintList = ColorStateList.valueOf(color)
                sb?.thumbTintList    = ColorStateList.valueOf(color)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // Trigger initial color
        seekRisk.progressTintList = ColorStateList.valueOf(riskColor(seekRisk.progress))
        seekRisk.thumbTintList    = ColorStateList.valueOf(riskColor(seekRisk.progress))

        // Photo picker
        layoutPhoto.setOnClickListener { pickPhoto.launch("image/*") }

        btnRotatePhoto.setOnClickListener {
            val path = localPhotoPath
            if (path.isBlank()) return@setOnClickListener
            try {
                val bmp = ProfilePhotoUtils.loadOrientedBitmap(path) ?: return@setOnClickListener
                val rotated = ProfilePhotoUtils.rotateBitmap(bmp, 90f)
                if (rotated != bmp) bmp.recycle()
                ProfilePhotoUtils.saveAsNormalizedJpeg(rotated, File(path))
                rotated.recycle()
                displayPhoto(path)
            } catch (_: Exception) {
                AppSnackbar.show(btnRotatePhoto, "Could not rotate photo.")
            }
        }

        // Save
        btnSave.setOnClickListener {
            val name = etFullName.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                etFullName.error = "Please enter your full name"
                etFullName.requestFocus()
                return@setOnClickListener
            }

            val user = User(
                fullName       = name,
                username       = etUsername.text?.toString()?.trim() ?: "",
                email          = etEmail.text?.toString()?.trim() ?: "",
                phone          = displayPhone.ifBlank { cached.phone },
                photoPath      = localPhotoPath,
                monthlyIncome  = etIncome.text?.toString()?.toDoubleOrNull() ?: 0.0,
                employmentType = spinnerEmploy.text?.toString()?.trim() ?: "",
                riskPreference = seekRisk.progress
            )

            btnSave.isEnabled = false
            btnSave.text = "Saving…"

            lifecycleScope.launch {
                try {
                    repository.saveUser(user)
                    ProfileStore.save(this@ProfileSetupActivity, user)
                    finish()
                } catch (e: Exception) {
                    btnSave.isEnabled = true
                    btnSave.text = "Save Profile"
                    AppSnackbar.showLong(btnSave, "Failed: ${e.message}")
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun displayPhoto(path: String) {
        val bmp = ProfilePhotoUtils.loadOrientedBitmap(path) ?: return
        val ivPhoto   = findViewById<ImageView>(R.id.ivPhoto)
        val tvInitials = findViewById<TextView>(R.id.tvInitials)
        ivPhoto.setImageBitmap(bmp)
        ivPhoto.visibility    = View.VISIBLE
        tvInitials.visibility = View.GONE
    }

    private fun updateRotatePhotoVisibility() {
        findViewById<MaterialButton>(R.id.btnRotatePhoto)?.visibility =
            if (localPhotoPath.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun initials(name: String): String =
        name.trim().split("\\s+".toRegex())
            .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
            .take(2).joinToString("")
            .ifBlank { "?" }

    private fun riskLabel(p: Int) = when {
        p < 33 -> "Conservative"
        p < 67 -> "Moderate"
        else   -> "Aggressive"
    }

    private fun riskColor(p: Int) = when {
        p < 33 -> getColor(R.color.success)
        p < 67 -> getColor(R.color.primary)
        else   -> getColor(R.color.error)
    }
}
