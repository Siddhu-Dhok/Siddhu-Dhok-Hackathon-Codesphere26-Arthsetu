package com.example.team_arthsetu.model

data class Asset(
    val id              : String = "",
    val userId          : String = "",
    val name            : String = "",
    /** stocks | mutual_fund | gold | crypto | real_estate | savings | fixed_deposit | other */
    val type            : String = "other",
    val value           : Double = 0.0,
    val note            : String = "",          // institution / fund name
    val changePercent   : Double = 0.0,         // % gain/loss (manually entered)
    val monthlyInvestment: Double = 0.0,        // SIP / monthly amount (0 = none)
    val isActive        : Boolean = true,
    val timestamp       : Long   = System.currentTimeMillis()
)
