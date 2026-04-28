package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.repository.FirestoreRepository
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full goal detail loaded from Firestore by [EXTRA_GOAL_ID].
 */
class GoalDetailActivity : AppCompatActivity() {

    private val repo = FirestoreRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goal_detail)

        val goalId = intent.getStringExtra(EXTRA_GOAL_ID).orEmpty()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarGoalDetail)
        val progress = findViewById<ProgressBar>(R.id.progressGoalDetail)
        val scroll = findViewById<NestedScrollView>(R.id.scrollGoalDetail)

        val tvName = findViewById<TextView>(R.id.tvDetailName)
        val tvDesc = findViewById<TextView>(R.id.tvDetailDescription)
        val tvTarget = findViewById<TextView>(R.id.tvDetailTarget)
        val tvCurrent = findViewById<TextView>(R.id.tvDetailCurrent)
        val tvDate = findViewById<TextView>(R.id.tvDetailTargetDate)
        val tvInsight = findViewById<TextView>(R.id.tvDetailInsight)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        if (goalId.isEmpty()) {
            finish()
            return
        }

        progress.visibility = View.VISIBLE
        scroll.visibility = View.GONE

        lifecycleScope.launch {
            val goal = withContext(Dispatchers.IO) {
                runCatching { repo.getGoal(goalId) }.getOrNull()
            }
            progress.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            if (goal == null) {
                tvName.text = "—"
                tvDesc.text = "Could not load this goal."
                return@launch
            }
            toolbar.title = goal.goalName.ifBlank { "Goal" }
            tvName.text = goal.goalName.ifBlank { "—" }
            tvDesc.text = goal.description.ifBlank { "—" }
            tvTarget.text = formatRupees(goal.targetAmount)
            tvCurrent.text = formatRupees(goal.currentAmount)
            tvDate.text = if (goal.targetDate > 0L) {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(goal.targetDate))
            } else {
                "—"
            }
            tvInsight.text = goal.aiInsight.ifBlank { "—" }
        }
    }

    private fun formatRupees(v: Double): String = when {
        v >= 1_00_00_000 -> "₹${String.format("%.2f", v / 1_00_00_000)} Cr"
        v >= 1_00_000 -> "₹${String.format("%.1f", v / 1_00_000)} L"
        v >= 1_000 -> "₹${String.format("%.0f", v / 1_000)} K"
        else -> "₹${v.toLong()}"
    }

    companion object {
        const val EXTRA_GOAL_ID = "goalId"
    }
}
