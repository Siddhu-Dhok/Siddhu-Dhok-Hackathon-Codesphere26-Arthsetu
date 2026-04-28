package com.example.team_arthsetu.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.team_arthsetu.utils.LoginNumericalCaptcha
import com.google.firebase.auth.PhoneAuthProvider

class AuthViewModel : ViewModel() {

    // Shared state between LoginFragment and OtpFragment
    var verificationId: String = ""
    var phoneNumber: String = ""
    var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    /** 6-digit captcha shown on login; verified locally before Phone Auth. */
    private var loginCaptchaAnswer: String = ""

    fun currentLoginCaptcha(): String {
        if (loginCaptchaAnswer.isEmpty()) {
            loginCaptchaAnswer = LoginNumericalCaptcha.generate()
        }
        return loginCaptchaAnswer
    }

    /** New captcha (Refresh button or after Change number from OTP). */
    fun refreshLoginCaptcha(): String {
        loginCaptchaAnswer = LoginNumericalCaptcha.generate()
        return loginCaptchaAnswer
    }

    fun verifyLoginCaptcha(input: String): Boolean = input.trim() == loginCaptchaAnswer

    private val _otpSent = MutableLiveData(false)
    val otpSent: LiveData<Boolean> = _otpSent

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun setLoading(on: Boolean)             { _isLoading.value = on }
    fun setError(msg: String?)              { _error.value = msg }
    fun setOtpSent(sent: Boolean)           { _otpSent.value = sent }
    fun clearError()                        { _error.value = null }
}
