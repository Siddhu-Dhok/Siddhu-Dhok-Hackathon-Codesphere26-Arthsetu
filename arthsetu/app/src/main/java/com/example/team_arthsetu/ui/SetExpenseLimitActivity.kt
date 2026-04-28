package com.example.team_arthsetu.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.team_arthsetu.R
import com.example.team_arthsetu.repository.FirestoreRepository
import com.example.team_arthsetu.utils.ExpenseLimitStore
import com.example.team_arthsetu.utils.NotificationHelper
import com.example.team_arthsetu.viewmodel.ExpenseViewModel
import kotlinx.coroutines.launch
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.example.team_arthsetu.utils.AppSnackbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar

class SetExpenseLimitActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_expense_limit)

        val toolbar         = findViewById<MaterialToolbar>(R.id.toolbar)
        val etDaily         = findViewById<TextInputEditText>(R.id.etDailyLimit)
        val etWeekly        = findViewById<TextInputEditText>(R.id.etWeeklyLimit)
        val etMonthly       = findViewById<TextInputEditText>(R.id.etMonthlyLimit)
        val etQuarterly     = findViewById<TextInputEditText>(R.id.etQuarterlyLimit)
        val tvDailySpend    = findViewById<TextView>(R.id.tvDailySpend)
        val tvWeeklySpend   = findViewById<TextView>(R.id.tvWeeklySpend)
        val tvMonthlySpend  = findViewById<TextView>(R.id.tvMonthlySpendCard)
        val tvQuarterSpend  = findViewById<TextView>(R.id.tvQuarterlySpend)
        val tvDailySt       = findViewById<TextView>(R.id.tvDailyStatus)
        val tvWeeklySt      = findViewById<TextView>(R.id.tvWeeklyStatus)
        val tvMonthlySt     = findViewById<TextView>(R.id.tvMonthlyStatus)
        val tvQuarterlySt   = findViewById<TextView>(R.id.tvQuarterlyStatus)
        val pbDaily         = findViewById<ProgressBar>(R.id.progressDaily)
        val pbWeekly        = findViewById<ProgressBar>(R.id.progressWeekly)
        val pbMonthly       = findViewById<ProgressBar>(R.id.progressMonthly)
        val pbQuarterly     = findViewById<ProgressBar>(R.id.progressQuarterly)
        val btnSave         = findViewById<MaterialButton>(R.id.btnSaveLimits)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Ensure notification channel exists
        NotificationHelper.createChannel(this)

        // Pre-fill saved limits
        prefill(etDaily,     ExpenseLimitStore.getDailyLimit(this))
        prefill(etWeekly,    ExpenseLimitStore.getWeeklyLimit(this))
        prefill(etMonthly,   ExpenseLimitStore.getMonthlyLimit(this))
        prefill(etQuarterly, ExpenseLimitStore.getQuarterlyLimit(this))

        // Show current spending from cached SMS/cloud data (loaded via ViewModel if available)
        val spends = computeCurrentSpends()
        bindPeriod(
            tvDailySpend,   "Today's spend",          spends.daily,
            ExpenseLimitStore.getDailyLimit(this),     pbDaily,   tvDailySt
        )
        bindPeriod(
            tvWeeklySpend,  "This week's spend",       spends.weekly,
            ExpenseLimitStore.getWeeklyLimit(this),    pbWeekly,  tvWeeklySt
        )
        bindPeriod(
            tvMonthlySpend, "This month's spend",      spends.monthly,
            ExpenseLimitStore.getMonthlyLimit(this),   pbMonthly, tvMonthlySt
        )
        bindPeriod(
            tvQuarterSpend, "This quarter's spend",    spends.quarterly,
            ExpenseLimitStore.getQuarterlyLimit(this), pbQuarterly, tvQuarterlySt
        )

        // Save button
        btnSave.setOnClickListener {
            val daily     = etDaily.text?.toString()?.toDoubleOrNull() ?: 0.0
            val weekly    = etWeekly.text?.toString()?.toDoubleOrNull() ?: 0.0
            val monthly   = etMonthly.text?.toString()?.toDoubleOrNull() ?: 0.0
            val quarterly = etQuarterly.text?.toString()?.toDoubleOrNull() ?: 0.0

            ExpenseLimitStore.setDailyLimit(this, daily)
            ExpenseLimitStore.setWeeklyLimit(this, weekly)
            ExpenseLimitStore.setMonthlyLimit(this, monthly)
            ExpenseLimitStore.setQuarterlyLimit(this, quarterly)

            // Sync to Firestore for Cloud Functions / FCM (no local limit push here)
            lifecycleScope.launch {
                try {
                    FirestoreRepository().syncExpenseLimitsFromLocal(this@SetExpenseLimitActivity)
                    FirestoreRepository().syncCategoryLimitsFromLocal(this@SetExpenseLimitActivity)
                } catch (_: Exception) { /* offline / rules — limits still in prefs */ }
            }

            AppSnackbar.show(btnSave, "✅  Expense limits saved!")
            btnSave.postDelayed({ finish() }, 900)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun prefill(et: TextInputEditText, value: Double) {
        if (value > 0) et.setText(value.toInt().toString())
    }

    private fun bindPeriod(
        tvSpend  : TextView,
        label    : String,
        spend    : Double,
        limit    : Double,
        pb       : ProgressBar,
        tvStatus : TextView
    ) {
        tvSpend.text = "$label: ₹${fmt(spend)}"

        if (limit > 0) {
            val pct = ((spend / limit) * 100).toInt().coerceIn(0, 100)
            pb.progress = pct

            val color = when {
                spend >= limit      -> getColor(R.color.error)
                pct >= 80           -> getColor(R.color.warning)
                else                -> getColor(R.color.primary)
            }
            pb.progressTintList = ColorStateList.valueOf(color)

            tvStatus.visibility = View.VISIBLE
            tvStatus.text = when {
                spend >= limit -> "⚠ Over limit!"
                pct >= 80      -> "⚠ ${100 - pct}% left"
                else           -> "✓ ${100 - pct}% left"
            }
            tvStatus.setTextColor(color)
        } else {
            pb.progress = 0
            tvStatus.visibility = View.GONE
        }
    }

    /**
     * Computes spending totals for each period from the shared ExpenseViewModel data.
     * Falls back to 0 if data isn't cached.
     */
    private fun computeCurrentSpends(): Spends {
        // Read from shared ViewModel singleton if available
        val all = try {
            ExpenseViewModel.cachedTransactions
        } catch (e: Exception) {
            emptyList()
        }
        if (all.isEmpty()) return Spends()

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val startOfWeek = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val startOfMonth = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val currentMonth = cal.get(Calendar.MONTH)
        val quarterStart = (currentMonth / 3) * 3
        val startOfQuarter = Calendar.getInstance().apply {
            set(Calendar.MONTH, quarterStart)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun sum(from: Long) = all.filter { it.type == "debit" && it.timestamp >= from }.sumOf { it.amount }

        return Spends(
            daily     = sum(startOfDay),
            weekly    = sum(startOfWeek),
            monthly   = sum(startOfMonth),
            quarterly = sum(startOfQuarter)
        )
    }

    private fun fmt(v: Double) = String.format("%.0f", v)

    data class Spends(
        val daily    : Double = 0.0,
        val weekly   : Double = 0.0,
        val monthly  : Double = 0.0,
        val quarterly: Double = 0.0
    )
}
