package com.example.team_arthsetu.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.team_arthsetu.R
import com.example.team_arthsetu.bankstatement.StatementTransaction
import com.example.team_arthsetu.bankstatement.StatementTransactionAdapter
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale
import kotlinx.coroutines.launch

class StatementCloudActivity : AppCompatActivity() {

    private val repository = FirestoreRepository()

    private lateinit var layoutContent: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvSumDebit: TextView
    private lateinit var tvSumCredit: TextView
    private lateinit var tvCount: TextView
    private lateinit var recycler: RecyclerView

    private val adapter = StatementTransactionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statement_cloud)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        layoutContent = findViewById(R.id.layoutContent)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvSumDebit = findViewById(R.id.tvSumDebit)
        tvSumCredit = findViewById(R.id.tvSumCredit)
        tvCount = findViewById(R.id.tvCount)
        recycler = findViewById(R.id.recyclerStatements)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        loadCloud()
    }

    private fun loadCloud() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                val rows = repository.getStatementTransactionsFromCloud()
                if (rows.isEmpty()) {
                    showEmpty()
                    return@launch
                }
                showContent()
                adapter.submitList(rows)
                updateSummary(rows)
            } catch (e: Exception) {
                showEmpty()
                AppSnackbar.showLong(layoutEmpty, "Failed to load: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun updateSummary(rows: List<StatementTransaction>) {
        var debit = 0.0
        var credit = 0.0
        for (t in rows) {
            when (t.type) {
                StatementTransaction.TYPE_CREDIT -> credit += t.amount
                else -> debit += t.amount
            }
        }
        tvSumDebit.text = formatRupee(debit)
        tvSumCredit.text = formatRupee(credit)
        tvCount.text = rows.size.toString()
    }

    private fun formatRupee(amount: Double): String =
        String.format(Locale.US, "₹%,.2f", amount)

    private fun setLoading(loading: Boolean) {
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showEmpty() {
        layoutEmpty.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE
    }

    private fun showContent() {
        layoutEmpty.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE
    }
}

