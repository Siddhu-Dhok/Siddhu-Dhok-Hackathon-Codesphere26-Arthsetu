package com.example.team_arthsetu.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.model.Goal
import com.example.team_arthsetu.repository.FirestoreRepository
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists saved goal names only; tap opens [GoalDetailActivity] with `goalId`.
 */
class GoalsListActivity : AppCompatActivity() {

    private val repo = FirestoreRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goals_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarGoalsList)
        val rv = findViewById<RecyclerView>(R.id.rvGoals)
        val progress = findViewById<ProgressBar>(R.id.progressGoalsList)
        val empty = findViewById<TextView>(R.id.tvGoalsListEmpty)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rv.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val goals = withContext(Dispatchers.IO) {
                runCatching { repo.getGoals() }.getOrDefault(emptyList())
            }
            progress.visibility = View.GONE
            if (goals.isEmpty()) {
                empty.visibility = View.VISIBLE
                rv.adapter = null
            } else {
                empty.visibility = View.GONE
                rv.adapter = GoalNameAdapter(goals) { goal ->
                    startActivity(
                        Intent(this@GoalsListActivity, GoalDetailActivity::class.java)
                            .putExtra(GoalDetailActivity.EXTRA_GOAL_ID, goal.id)
                    )
                }
            }
        }
    }
}

private class GoalNameAdapter(
    private val goals: List<Goal>,
    private val onClick: (Goal) -> Unit
) : RecyclerView.Adapter<GoalNameAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvGoalName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_goal_name, parent, false)
        return VH(v)
    }

    override fun getItemCount() = goals.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = goals[position]
        holder.name.text = g.goalName.ifBlank { "Untitled goal" }
        holder.itemView.setOnClickListener { onClick(g) }
    }
}
