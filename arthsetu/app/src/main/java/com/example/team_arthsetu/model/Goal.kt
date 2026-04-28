package com.example.team_arthsetu.model

/**
 * Firestore: `users/{uid}/goals/{goalId}`
 *
 * Fields: goalName, description, targetAmount, currentAmount, targetDate, aiInsight, createdAt (+ userId)
 */
data class Goal(
    val id: String = "",
    val userId: String = "",
    val goalName: String = "",
    val description: String = "",
    val targetAmount: Double = 0.0,
    val currentAmount: Double = 0.0,
    /** Target date (end of plan) in epoch millis */
    val targetDate: Long = 0L,
    val aiInsight: String = "",
    /** Set on read from Firestore [com.google.firebase.Timestamp] */
    val createdAt: Long = 0L
)
