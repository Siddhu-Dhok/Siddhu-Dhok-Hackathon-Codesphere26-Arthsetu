package com.example.team_arthsetu.model

data class Loan(
    val id: String = "",
    val userId: String = "",
    val lenderName: String = "",
    val loanType: String = "Home Loan",
    val totalAmount: Double = 0.0,
    val interestRate: Double = 0.0,
    val tenure: Int = 0,
    val emiAmount: Double = 0.0,
    val emiDueDate: String = "",
    val startDate: String = ""
)
