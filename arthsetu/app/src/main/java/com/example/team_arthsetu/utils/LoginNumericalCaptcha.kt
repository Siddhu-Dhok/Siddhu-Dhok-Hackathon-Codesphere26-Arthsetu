package com.example.team_arthsetu.utils

import java.security.SecureRandom

/**
 * Generates a 6-digit numeric captcha for the login screen (client-side verification before OTP).
 */
object LoginNumericalCaptcha {

    private val random = SecureRandom()

    /** Inclusive range 100000–999999. */
    fun generate(): String {
        val n = 100_000 + random.nextInt(900_000)
        return n.toString()
    }
}
