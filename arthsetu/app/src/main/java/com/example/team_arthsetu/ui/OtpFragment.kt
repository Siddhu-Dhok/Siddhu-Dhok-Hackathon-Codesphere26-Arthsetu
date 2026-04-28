package com.example.team_arthsetu.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.team_arthsetu.R
import com.example.team_arthsetu.fcm.FcmTokenSync
import com.example.team_arthsetu.viewmodel.AuthViewModel
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class OtpFragment : Fragment(R.layout.fragment_otp) {

    private val auth = FirebaseAuth.getInstance()
    private val authViewModel: AuthViewModel by activityViewModels()
    private var countDownTimer: CountDownTimer? = null

    /** Match Firebase auto-retrieval window; resend enabled after 1 minute. */
    private companion object {
        const val RESEND_COOLDOWN_MS = 60_000L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvPhoneInfo   = view.findViewById<TextView>(R.id.tvPhoneInfo)
        val btnVerify     = view.findViewById<Button>(R.id.btnVerify)
        val btnResend     = view.findViewById<Button>(R.id.btnResend)
        val btnChange     = view.findViewById<Button>(R.id.btnChangeNumber)
        val tvTimer       = view.findViewById<TextView>(R.id.tvTimer)
        val layoutLoading = view.findViewById<LinearLayout>(R.id.layoutLoading)

        // OTP boxes
        val boxes = listOf(
            view.findViewById<EditText>(R.id.otp1),
            view.findViewById<EditText>(R.id.otp2),
            view.findViewById<EditText>(R.id.otp3),
            view.findViewById<EditText>(R.id.otp4),
            view.findViewById<EditText>(R.id.otp5),
            view.findViewById<EditText>(R.id.otp6)
        )

        tvPhoneInfo.text = "OTP sent to ${authViewModel.phoneNumber}"
        startTimer(tvTimer, btnResend)
        setupOtpBoxes(boxes)
        boxes[0].requestFocus()

        authViewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
            btnVerify.isEnabled = !loading
            boxes.forEach { it.isEnabled = !loading }
        }

        authViewModel.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                AppSnackbar.showLong(view, msg)
                authViewModel.clearError()
                boxes.forEach { it.text.clear() }
                boxes[0].requestFocus()
            }
        }

        btnVerify.setOnClickListener {
            val otp = boxes.joinToString("") { it.text.toString() }
            if (otp.length != 6) {
                AppSnackbar.show(view, "Please enter all 6 digits")
                return@setOnClickListener
            }
            verifyOtp(otp)
        }

        btnResend.setOnClickListener {
            btnResend.isEnabled = false
            boxes.forEach { it.text.clear() }
            boxes[0].requestFocus()
            resendOtp(tvTimer, btnResend)
        }

        btnChange.setOnClickListener {
            authViewModel.refreshLoginCaptcha()
            findNavController().navigateUp()
        }
    }

    // ── Auto-advance between boxes ─────────────────────────────────────────

    private fun setupOtpBoxes(boxes: List<EditText>) {
        boxes.forEachIndexed { index, box ->

            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1) {
                        // Move to next box
                        if (index < boxes.size - 1) boxes[index + 1].requestFocus()
                        else boxes[index].clearFocus() // last box — dismiss keyboard
                    }
                }
            })

            // Handle backspace to go back to previous box
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL
                    && event.action == KeyEvent.ACTION_DOWN
                    && box.text.isEmpty()
                    && index > 0
                ) {
                    boxes[index - 1].requestFocus()
                    boxes[index - 1].text.clear()
                    return@setOnKeyListener true
                }
                false
            }
        }
    }

    // ── OTP Verification ──────────────────────────────────────────────────

    private fun verifyOtp(otp: String) {
        if (authViewModel.verificationId.isEmpty()) {
            authViewModel.setError("Session expired. Please go back and try again.")
            return
        }
        authViewModel.setLoading(true)
        val credential = PhoneAuthProvider.getCredential(authViewModel.verificationId, otp)
        signIn(credential)
    }

    private fun signIn(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                authViewModel.setLoading(false)
                FcmTokenSync.requestTokenAndSyncToFirestore(requireContext().applicationContext)
                findNavController().navigate(R.id.action_otp_to_dashboard)
            }
            .addOnFailureListener { e ->
                authViewModel.setLoading(false)
                val msg = when {
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Incorrect OTP. Please check and try again."
                    e.message?.contains("expired", ignoreCase = true) == true ->
                        "OTP has expired. Please request a new one."
                    else -> e.message ?: "Verification failed"
                }
                authViewModel.setError(msg)
            }
    }

    // ── Resend OTP ────────────────────────────────────────────────────────

    private fun resendOtp(tvTimer: TextView, btnResend: Button) {
        if (authViewModel.phoneNumber.isEmpty() || authViewModel.resendToken == null) return
        authViewModel.setLoading(true)

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(authViewModel.phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setForceResendingToken(authViewModel.resendToken!!)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    authViewModel.setLoading(false)
                    signIn(credential)
                }
                override fun onVerificationFailed(e: FirebaseException) {
                    authViewModel.setLoading(false)
                    authViewModel.setError(e.message ?: "Resend failed")
                }
                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    authViewModel.verificationId = id
                    authViewModel.resendToken    = token
                    authViewModel.setLoading(false)
                    AppSnackbar.show(requireView(), "OTP resent successfully!")
                    startTimer(tvTimer, btnResend)
                }
            }).build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    // ── Countdown Timer ───────────────────────────────────────────────────

    private fun startTimer(tvTimer: TextView, btnResend: Button) {
        countDownTimer?.cancel()
        btnResend.isEnabled = false

        countDownTimer = object : CountDownTimer(RESEND_COOLDOWN_MS, 1_000) {
            override fun onTick(ms: Long) {
                val totalSec = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
                val mm = totalSec / 60
                val ss = totalSec % 60
                tvTimer.text = getString(R.string.otp_resend_timer, mm, ss)
            }

            override fun onFinish() {
                tvTimer.text = getString(R.string.otp_resend_ready)
                btnResend.isEnabled = true
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
    }
}
