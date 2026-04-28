package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
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

class LoginFragment : Fragment(R.layout.fragment_login) {

    private val auth = FirebaseAuth.getInstance()
    private val authViewModel: AuthViewModel by activityViewModels()

    // ── Force adjustResize while this screen is visible so the white card
    //    never disappears behind the keyboard. Other screens restore adjustPan.
    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        requireActivity().window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        if (auth.currentUser == null) {
            view?.findViewById<TextView>(R.id.tvCaptchaCode)?.text = authViewModel.currentLoginCaptcha()
        }
    }

    @Suppress("DEPRECATION")
    override fun onPause() {
        super.onPause()
        requireActivity().window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (auth.currentUser != null) {
            FcmTokenSync.requestTokenAndSyncToFirestore(requireContext().applicationContext)
            findNavController().navigate(R.id.action_login_to_dashboard)
            return
        }

        val etPhone          = view.findViewById<EditText>(R.id.etPhone)
        val etCaptcha        = view.findViewById<EditText>(R.id.etCaptcha)
        val tvCaptchaCode    = view.findViewById<TextView>(R.id.tvCaptchaCode)
        val btnRefreshCaptcha = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRefreshCaptcha)
        val btnSend          = view.findViewById<Button>(R.id.btnSendOtp)
        val layoutLoading    = view.findViewById<LinearLayout>(R.id.layoutLoading)

        fun showCaptcha() {
            tvCaptchaCode.text = authViewModel.currentLoginCaptcha()
        }
        showCaptcha()

        btnRefreshCaptcha.setOnClickListener {
            tvCaptchaCode.text = authViewModel.refreshLoginCaptcha()
            etCaptcha.text?.clear()
            etCaptcha.error = null
        }

        authViewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
            btnSend.isEnabled = !loading
            btnSend.alpha = if (loading) 0.6f else 1f
            btnRefreshCaptcha.isEnabled = !loading
            etCaptcha.isEnabled = !loading
            etPhone.isEnabled = !loading
        }

        authViewModel.error.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                AppSnackbar.showLong(view, msg)
                authViewModel.clearError()
            }
        }

        authViewModel.otpSent.observe(viewLifecycleOwner) { sent ->
            if (sent) {
                authViewModel.setOtpSent(false)
                findNavController().navigate(R.id.action_login_to_otp)
            }
        }

        btnSend.setOnClickListener {
            val number = etPhone.text.toString().trim()
            if (number.length != 10) {
                etPhone.error = "Enter a valid 10-digit number"
                etPhone.requestFocus()
                return@setOnClickListener
            }
            val captchaInput = etCaptcha.text?.toString().orEmpty()
            if (captchaInput.length != 6) {
                etCaptcha.error = getString(R.string.login_captcha_hint)
                etCaptcha.requestFocus()
                return@setOnClickListener
            }
            if (!authViewModel.verifyLoginCaptcha(captchaInput)) {
                etCaptcha.error = getString(R.string.login_captcha_mismatch)
                etCaptcha.requestFocus()
                return@setOnClickListener
            }
            etCaptcha.error = null
            val fullPhone = "+91$number"
            authViewModel.phoneNumber = fullPhone
            sendOtp(fullPhone)
        }
    }

    private fun sendOtp(phoneNumber: String) {
        authViewModel.setLoading(true)

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    authViewModel.setLoading(false)
                    signInDirectly(credential)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    authViewModel.setLoading(false)
                    val raw = e.message ?: ""
                    val msg = when {
                        raw.contains("not allowed", ignoreCase = true) ||
                        raw.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                            "Phone sign-in is disabled. Enable it in Firebase Console → Authentication → Sign-in method → Phone."
                        raw.contains("not authorized", ignoreCase = true) ->
                            "App not authorized. Add SHA-1 fingerprint in Firebase Console → Project Settings."
                        raw.contains("quota", ignoreCase = true) ->
                            "Too many requests. Please try again later."
                        raw.contains("invalid", ignoreCase = true) ->
                            "Invalid phone number. Please check and try again."
                        raw.contains("network", ignoreCase = true) ->
                            "No internet connection. Please check your network."
                        else -> "Failed to send OTP: $raw"
                    }
                    authViewModel.setError(msg)
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    authViewModel.verificationId = verificationId
                    authViewModel.resendToken    = token
                    authViewModel.setLoading(false)
                    authViewModel.setOtpSent(true)
                }
            }).build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun signInDirectly(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                FcmTokenSync.requestTokenAndSyncToFirestore(requireContext().applicationContext)
                findNavController().navigate(R.id.action_login_to_dashboard)
            }
            .addOnFailureListener { e ->
                authViewModel.setError(e.message ?: "Sign-in failed")
            }
    }
}
