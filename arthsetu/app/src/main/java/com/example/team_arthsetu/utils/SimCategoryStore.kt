package com.example.team_arthsetu.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-defined simulation category chips: display title + emoji, mapped to a built-in engine scenario
 * ([baseScenarioName] is `Scenario.name`, e.g. JOB_LOSS).
 */
data class UserSimCategory(
    val id: String,
    val title: String,
    val emoji: String,
    val baseScenarioName: String
)

object SimCategoryStore {

    private const val PREFS = "sim_category_prefs"
    private const val KEY_LIST = "user_sim_categories"

    fun getAll(context: Context): List<UserSimCategory> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserSimCategory(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    emoji = o.optString("emoji", "✨"),
                    baseScenarioName = o.getString("base")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** New chips use the Job Loss form by default; user can switch scenario with the top chips. */
    fun add(context: Context, title: String, emoji: String): UserSimCategory =
        add(context, title, emoji, DEFAULT_BASE)

    private const val DEFAULT_BASE = "JOB_LOSS"

    fun add(context: Context, title: String, emoji: String, baseScenarioName: String): UserSimCategory {
        val list = getAll(context).toMutableList()
        val id = UUID.randomUUID().toString()
        val created = UserSimCategory(
            id = id,
            title = title.trim(),
            emoji = emoji.trim().ifBlank { "✨" },
            baseScenarioName = baseScenarioName
        )
        list.add(created)
        saveList(context, list)
        return created
    }

    private fun saveList(context: Context, list: List<UserSimCategory>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("emoji", c.emoji)
                    put("base", c.baseScenarioName)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }
}
