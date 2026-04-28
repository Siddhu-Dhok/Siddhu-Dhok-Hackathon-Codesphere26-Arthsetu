package com.example.team_arthsetu.model

data class Liability(
    val id           : String = "",
    val userId       : String = "",
    val name         : String = "",
    /** home_loan | car_loan | personal_loan | credit_card | education_loan | other */
    val type         : String = "other",
    val amount       : Double = 0.0,
    val bank         : String = "",             // lender / bank name
    val interestRate : Double = 0.0,            // annual interest rate in % (0 = not set)
    val nextEmiDate  : String = "",             // optional, e.g. "OCT 05"
    val emiAmount    : Double = 0.0,
    val timestamp    : Long   = System.currentTimeMillis()
)
