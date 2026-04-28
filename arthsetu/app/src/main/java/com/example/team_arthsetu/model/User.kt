package com.example.team_arthsetu.model

data class User(
    val id             : String = "",
    val fullName       : String = "",
    val username       : String = "",
    val email          : String = "",
    val phone          : String = "",
    val photoPath      : String = "",       // local file path saved on device
    val monthlyIncome  : Double = 0.0,
    val employmentType : String = "",
    /** 0 = Conservative  |  50 = Moderate  |  100 = Aggressive */
    val riskPreference : Int    = 50,
    val createdAt      : Long   = System.currentTimeMillis()
)
