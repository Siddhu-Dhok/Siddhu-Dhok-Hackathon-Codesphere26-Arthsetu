package com.example.team_arthsetu.engine

import com.example.team_arthsetu.model.SimInput

/**
 * Generates human-readable insight strings from simulation inputs + results.
 * Pure Kotlin — no Android dependencies.
 */
object InsightsEngine {

    // ── Job Loss Insights ─────────────────────────────────────────────────────

    fun forJobLoss(
        input         : SimInput.JobLoss,
        survivalMonths: Int,
        monthlyBurn   : Double
    ): List<String> {
        val list = mutableListOf<String>()

        // Survival headline
        when {
            survivalMonths in 1..2 ->
                list.add("🚨 CRITICAL: Funds will run out in $survivalMonths month(s). Immediate action required.")
            survivalMonths in 3..6 ->
                list.add("⚠️ WARNING: You have only $survivalMonths months of runway. Start job search now.")
            survivalMonths in 7..12 ->
                list.add("⏳ You have $survivalMonths months before funds run out. Use this time wisely.")
            survivalMonths > 12 ->
                list.add("📅 Funds last $survivalMonths months (${survivalMonths / 12} year${if (survivalMonths >= 24) "s" else ""}). Good runway, but don't delay job search.")
            else ->
                list.add("✅ Your fallback income covers all expenses. Financial position is stable even without primary income.")
        }

        // Emergency fund adequacy
        val recommended3m = monthlyBurn * 3
        val recommended6m = monthlyBurn * 6
        if (input.savings < recommended3m && monthlyBurn > 0) {
            list.add("⚠️ Emergency fund is insufficient. Recommended minimum: ${fmt(recommended3m)} (3 months) — ideally ${fmt(recommended6m)} (6 months).")
        }

        // Expense cut suggestion
        if (survivalMonths in 1..12 && monthlyBurn > 0) {
            val cut20       = input.monthlyExpenses * 0.20
            val newBurn     = (monthlyBurn - cut20).coerceAtLeast(0.01)
            val extended    = if (newBurn > 0) (input.savings / newBurn).toInt() else 999
            list.add("💡 Cutting expenses by 20% (${fmt(cut20)}/month) extends survival to $extended months.")
        }

        // EMI moratorium tip
        if (input.emiPayments > 0) {
            list.add("💡 EMI Tip: Contact your bank for a 3–6 month moratorium on the ${fmt(input.emiPayments)}/month EMI. This can be arranged without credit score impact during job loss.")
        }

        // Fallback income suggestion
        if (input.fallbackIncome < input.monthlyExpenses * 0.3) {
            list.add("💡 Even partial freelance/consulting income of ${fmt(input.monthlyExpenses * 0.3)}/month would extend your runway significantly.")
        }

        return list
    }

    // ── Medical Emergency Insights ────────────────────────────────────────────

    fun forMedical(
        input       : SimInput.MedicalEmergency,
        debtRequired: Double,
        netCost     : Double
    ): List<String> {
        val list = mutableListOf<String>()

        // Coverage analysis
        val coveragePct = if (input.emergencyCost > 0)
            (input.insuranceCoverage / input.emergencyCost * 100).toInt() else 0

        when {
            coveragePct >= 100 ->
                list.add("✅ Insurance fully covers this emergency. Net out-of-pocket cost: ₹0.")
            coveragePct >= 70 ->
                list.add("✅ Insurance covers $coveragePct% of the cost. Net payable: ${fmt(netCost)}.")
            coveragePct in 30..69 ->
                list.add("⚠️ Insurance covers only $coveragePct% (${fmt(input.insuranceCoverage)}). You must arrange ${fmt(netCost)} out of pocket.")
            else ->
                list.add("🚨 Insurance covers less than 30% of this emergency. Significant out-of-pocket expense.")
        }

        // Debt warning
        if (debtRequired > 0) {
            list.add("🚨 Emergency cost exceeds savings by ${fmt(debtRequired)}. You will need a loan or family support.")
            list.add("💡 Medical loan options: Bajaj Finserv Health EMI (0–14%), HDFC Medical Loan, or employer advance.")
        }

        // Low emergency fund
        if (input.savings < 1_00_000) {
            list.add("⚠️ Savings below ₹1L. Build a dedicated medical buffer of at least ₹2–3L in a liquid fund.")
        } else if (input.savings < 3_00_000) {
            list.add("💡 Consider increasing health insurance coverage to ₹5–10L to better protect your ₹${fmt(input.savings)} savings.")
        }

        // Recovery timeline
        val monthlySurplus = input.monthlyIncome - input.monthlyExpenses
        if (debtRequired > 0 && monthlySurplus > 0) {
            val months = (debtRequired / monthlySurplus).toInt()
            list.add("📅 At your current savings rate of ${fmt(monthlySurplus)}/month, you'll recover the shortfall in ~$months months.")
        } else if (monthlySurplus <= 0) {
            list.add("⚠️ Your monthly expenses exceed income — additional cuts are needed during recovery.")
        }

        // Insurance upgrade suggestion
        if (coveragePct < 80) {
            val premium = (input.emergencyCost * 0.02).coerceAtMost(20_000.0)
            list.add("💡 Upgrading your health insurance to cover ${fmt(input.emergencyCost)} would cost roughly ${fmt(premium)}/year in premium — a small price vs. the risk.")
        }

        return list
    }

    // ── Income Drop Insights ──────────────────────────────────────────────────

    fun forIncomeDrop(
        input         : SimInput.IncomeDrop,
        survivalMonths: Int,
        requiredCut   : Double,
        reducedIncome : Double
    ): List<String> {
        val list    = mutableListOf<String>()
        val dropPct = 100 - input.reducedIncomePercent

        // Headline
        when {
            survivalMonths in 1..3 ->
                list.add("🚨 CRITICAL: A $dropPct% income drop depletes funds in $survivalMonths month(s). Immediate restructuring needed.")
            survivalMonths in 4..6 ->
                list.add("⚠️ Only $survivalMonths months of runway after a $dropPct% income drop. Plan cuts now.")
            survivalMonths in 7..18 ->
                list.add("⏳ $survivalMonths months of runway at reduced income. Bridge the gap with expense cuts or side income.")
            survivalMonths > 18 ->
                list.add("📅 Funds last $survivalMonths months at reduced income — you have reasonable time to adapt.")
            else ->
                list.add("✅ Even with a $dropPct% income drop, your reduced income covers all expenses. Financially stable.")
        }

        // Required expense cut
        if (requiredCut > 0) {
            val cutPct = (requiredCut / input.monthlyExpenses * 100).toInt().coerceAtMost(100)
            list.add("✂️ To break even on reduced income, cut expenses by ${fmt(requiredCut)}/month ($cutPct%).")
            list.add("💡 Start with: Cancel subscriptions (${fmt(requiredCut * 0.15)}+), reduce dining out (${fmt(requiredCut * 0.25)}), pause discretionary shopping (${fmt(requiredCut * 0.20)}).")
        }

        // EMI overload warning
        val emiRatio = if (reducedIncome > 0) input.emiPayments / reducedIncome else 0.0
        if (emiRatio > 0.40) {
            list.add("⚠️ EMIs (${fmt(input.emiPayments)}) are ${(emiRatio * 100).toInt()}% of reduced income — exceeds the healthy 40% limit. Seek loan restructuring.")
        }

        // Income recovery tips
        list.add("💡 Supplement income: freelancing (${fmt(input.currentIncome * 0.15)}/month target), consulting, tutoring, or monetising a skill can partially offset the gap.")

        // Savings buffer check
        if (input.savings < input.monthlyExpenses * 3) {
            list.add("⚠️ Emergency buffer is below 3 months of expenses. Avoid non-essential spending to preserve runway.")
        }

        return list
    }

    // ── Investment Crash Insights ─────────────────────────────────────────────

    fun forInvestmentCrash(
        input         : SimInput.InvestmentCrash,
        crashLoss     : Double,
        recoveryMonths: Int
    ): List<String> {
        val list     = mutableListOf<String>()
        val dropPct  = input.crashPercent

        // Headline
        list.add("📉 A $dropPct% market crash wipes ${fmt(crashLoss)} from your portfolio instantly, leaving ${fmt(input.portfolioValue - crashLoss)}.")

        when {
            recoveryMonths in 1..12 ->
                list.add("✅ Good news: At ${input.expectedReturnPct}% p.a. + ${fmt(input.monthlyContribution)}/month SIP, full recovery takes only $recoveryMonths months.")
            recoveryMonths in 13..36 ->
                list.add("⏳ Recovery takes $recoveryMonths months (~${recoveryMonths / 12} year${if (recoveryMonths >= 24) "s" else ""}). Stay invested — panic-selling locks in losses.")
            recoveryMonths > 36 ->
                list.add("⚠️ Recovery projected at $recoveryMonths months. Increase monthly SIP or review asset allocation to speed up recovery.")
            else ->
                list.add("🚨 At current contribution, full recovery exceeds 10 years. Significantly increase monthly SIP immediately.")
        }

        // Historical context
        list.add("📊 Context: Nifty 50 recovered ~18 months after the 2020 COVID crash (35% drop). A $dropPct% correction is ${if (dropPct <= 20) "a typical correction — stay calm." else "severe but historically recoverable."}")

        // DCA tip
        if (input.monthlyContribution > 0) {
            list.add("💡 Dollar-Cost Averaging (DCA): Your ${fmt(input.monthlyContribution)}/month SIP during the crash buys more units at lower prices — this accelerates recovery significantly.")
        } else {
            list.add("💡 Start a monthly SIP now — even ${fmt(input.portfolioValue * 0.01)}/month during the crash maximises recovery speed.")
        }

        // Rebalancing tip
        list.add("💡 Rebalance: If equity drops to <40% of portfolio, shift liquid/debt funds into equity to maintain target allocation and speed recovery.")

        return list
    }

    // ── EMI Rate Hike Insights ────────────────────────────────────────────────

    fun forEmiRateHike(
        input         : SimInput.EmiRateHike,
        extraMonthly  : Double,
        newEmi        : Double,
        survivalMonths: Int,
        requiredCut   : Double
    ): List<String> {
        val list    = mutableListOf<String>()
        val hike    = input.rateHikePercent

        list.add("📈 A ${hike}% rate hike on ₹${fmt(input.loanOutstanding)} outstanding increases your EMI from ${fmt(input.currentEmi)} → ${fmt(newEmi)}/month (+${fmt(extraMonthly)}).")

        val annualBurden = extraMonthly * 12
        list.add("💸 Extra annual loan burden: ${fmt(annualBurden)}. Over 3 years: ${fmt(annualBurden * 3)}.")

        when {
            survivalMonths in 1..6 ->
                list.add("🚨 CRITICAL: Savings deplete in $survivalMonths months at the new EMI. Immediate refinancing or prepayment needed.")
            survivalMonths in 7..18 ->
                list.add("⚠️ Only $survivalMonths months of runway at increased EMI. Start expense cuts now.")
            survivalMonths < 0 ->
                list.add("✅ Income still covers the higher EMI. Financial position remains stable.")
        }

        if (requiredCut > 0) {
            list.add("✂️ Cut monthly expenses by ${fmt(requiredCut)} to break even with the new EMI burden.")
        }

        // Prepayment tip
        val prepay = input.loanOutstanding * 0.10
        list.add("💡 Prepayment tip: Paying ${fmt(prepay)} (10% of outstanding) now reduces principal, lowering future EMI impact by ~₹${(prepay * hike / 100.0 / 12.0).toInt()}/month.")

        // Refinancing tip
        list.add("💡 Compare refinancing: Check if another bank offers your loan at 0.5–1% lower — this could save ${fmt(input.loanOutstanding * 0.005)} annually.")

        return list
    }

    // ── Formatter ─────────────────────────────────────────────────────────────

    internal fun fmt(v: Double): String = when {
        v >= 1_00_00_000 -> "₹${String.format("%.1f", v / 1_00_00_000)}Cr"
        v >= 1_00_000    -> "₹${String.format("%.1f", v / 1_00_000)}L"
        v >= 1_000       -> "₹${String.format("%.0f", v / 1_000)}K"
        else             -> "₹${v.toInt()}"
    }
}
