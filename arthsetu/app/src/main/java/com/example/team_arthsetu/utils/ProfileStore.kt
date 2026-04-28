package com.example.team_arthsetu.utils

import android.content.Context
import com.example.team_arthsetu.model.User

/** Lightweight SharedPreferences cache so the nav-drawer header loads instantly. */
object ProfileStore {

    private const val PREF = "user_profile"

    fun save(ctx: Context, user: User) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("fullName",       user.fullName)
            .putString("username",       user.username)
            .putString("email",          user.email)
            .putString("phone",          user.phone)
            .putString("photoPath",      user.photoPath)
            .putFloat ("monthlyIncome",  user.monthlyIncome.toFloat())
            .putString("employmentType", user.employmentType)
            .putInt   ("riskPreference", user.riskPreference)
            .apply()
    }

    fun load(ctx: Context): User {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return User(
            fullName       = p.getString("fullName",       "") ?: "",
            username       = p.getString("username",       "") ?: "",
            email          = p.getString("email",          "") ?: "",
            phone          = p.getString("phone",          "") ?: "",
            photoPath      = p.getString("photoPath",      "") ?: "",
            monthlyIncome  = p.getFloat ("monthlyIncome",  0f).toDouble(),
            employmentType = p.getString("employmentType", "") ?: "",
            riskPreference = p.getInt   ("riskPreference", 50)
        )
    }

    fun getName(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("fullName", "") ?: ""

    fun getPhone(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("phone", "") ?: ""

    fun getPhotoPath(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("photoPath", "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
