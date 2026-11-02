package com.myfinance.app.logic

import com.myfinance.app.data.Cat
import com.myfinance.app.data.Loans
import com.myfinance.app.data.Snapshot
import com.myfinance.app.model.Advice
import com.myfinance.app.model.StockBatch

/** A loan or instalment that needs paying, from SMS or a manual loan. */
data class DueItem(val lender: String, val amount: Double, val due: Long?, val manual: Boolean)

object Advisor {

    fun dueItems(s: Snapshot, now: Long = System.currentTimeMillis()): List<DueItem> {
        val sms = s.loans.map { DueItem(it.lender, it.outstanding, it.due, false) }
        val manual = s.manualLoans.filter { it.second > 0 }.map { (l, rem) ->
            DueItem(l.lender, minOf(l.monthly, rem), Analytics.nextDue(l, now), true)
        }
        return (sms + manual).sortedBy { it.due ?: Long.MAX_VALUE }
    }

    fun advise(
        s: Snapshot,
        budgets: Map<String, Double>,
        weeklyAllowance: Double,
        stock: List<StockBatch>,
        now: Long = System.currentTimeMillis(),
    ): List<Advice> {
        val out = ArrayList<Advice>()
        val (ms, me) = Analytics.monthRange(0, now)
        val m = Analytics.month(s.txs, ms, me)
        val (ps, pe) = Analytics.monthRange(-1, now)
        val prev = Analytics.month(s.txs, ps, pe)
        val cash = s.cash

        // 1. Due soon (and money due to you in the same window)
        val due = dueItems(s, now)
        val soon = due.filter { it.due != null && Analytics.daysUntil(it.due, now) <= 7 }
        val incoming = s.receivables.filter { it.second > 0 && Analytics.daysUntil(it.first.due, now) <= 7 }
            .map { (r, left) -> Triple(r.person, left, r.due) } +
            s.pending.filter { it.second > 0 && Analytics.daysUntil(it.first.due, now) in -14..7 }
                .map { (p, left) -> Triple("${p.gig} (side gig)", left, p.due) }
        val expected = incoming.sumOf { it.second }
        if (soon.isNotEmpty()) {
            val total = soon.sumOf { it.amount }
            val lines = soon.joinToString("\n") { d ->
                val n = Analytics.daysUntil(d.due!!, now)
                val w = when { n < 0 -> "OVERDUE ${-n}d"; n == 0 -> "due TODAY"; else -> "due in ${n}d (${fmt(d.due)})" }
                "• ${d.lender}: ${ksh(d.amount)} — $w"
            }
            val inLines = if (incoming.isEmpty()) "" else "\nExpected in: " + incoming.joinToString(", ") { (who, left, d) -> "$who ${ksh(left)} (${fmt(d)})" }
            val have = cash + expected
            val gap = when {
                cash >= total -> "\nYou have ${ksh(cash)} across M-Pesa, Pochi and the bank, enough to cover it."
                have >= total -> "\nYou have ${ksh(cash)} now; with the ${ksh(expected)} you're owed it's covered — make sure they pay on time."
                else -> "\nYou have ${ksh(cash)}${if (expected > 0) " + ${ksh(expected)} expected" else ""} — short by ${ksh(total - have)}. Don't cover the gap with a new loan; cut spending this week."
            }
            val urgent = soon.any { Analytics.daysUntil(it.due!!, now) <= 2 }
            out += Advice(if (urgent) 3 else 2, "${ksh(total)} due in the next 7 days", lines + inLines + gap, tag = "due")
        }

        // 1b. People who owe you
        s.receivables.filter { it.second > 0 }.forEach { (r, left) ->
            val n = Analytics.daysUntil(r.due, now)
            when {
                n < 0 -> out += Advice(3, "${r.person} is ${-n} day${if (n == -1) "" else "s"} late", "${ksh(left)} was due ${fmt(r.due)}. Follow up today — you're counting on it for your own repayments.")
                n <= 1 -> out += Advice(1, "${r.person} owes you ${ksh(left)} ${if (n == 0) "today" else "tomorrow"}", "A friendly reminder now makes it more likely to land on time.")
            }
        }

        // 1c. Side-gig pay that's late
        s.pending.filter { it.second > 0 }.forEach { (p, left) ->
            val n = Analytics.daysUntil(p.due, now)
            if (n < 0) out += Advice(
                if (n < -7) 3 else 2, "Side gig unpaid: ${p.gig}",
                "${ksh(left)}${if (p.payer.isNotBlank()) " from ${p.payer}" else ""} was expected ${fmt(p.due)} (${-n} day${if (n == -1) "" else "s"} ago). " +
                    "Follow up today, and don't spend it until it lands.",
            )
        }

        // 1d. Side-gig earnings this month: keep them working, not leaking into daily spending
        val side = m.txs.filter { it.category == com.myfinance.app.data.Cat.SIDE && it.type == com.myfinance.app.model.TxType.RECEIVED }
        if (side.isNotEmpty()) {
            val total = side.sumOf { it.amount }
            val byGig = side.groupBy { it.account.ifBlank { it.counterparty } }.map { (g, l) -> "• $g: ${ksh(l.sumOf { it.amount })}" }
            val waiting = s.pending.sumOf { it.second }
            out += Advice(
                0, "Side gigs earned ${ksh(total)} this month",
                byGig.joinToString("\n") + (if (waiting > 0) "\nStill waiting on ${ksh(waiting)}." else "") +
                    "\nPut it straight into the business or clearing expensive loans — not into daily spending.",
            )
        }

        // 2. Short-term debt and payment order
        val smsDebt = s.loans.sumOf { it.outstanding }
        if (smsDebt > 0) {
            val order = s.loans.sortedBy { Loans.COST_RANK.indexOf(it.lender).let { i -> if (i < 0) 99 else i } }
            out += Advice(
                2, "You owe ${ksh(smsDebt)} in short-term loans",
                "Pay in this order (most expensive first):\n" +
                    order.joinToString("\n") { "• ${it.lender}: ${ksh(it.outstanding)}" } +
                    "\nHustler/Bridge is the cheapest (~0.8%/month) — pay it last if money is tight.",
            )
        } else {
            out += Advice(0, "No short-term loans outstanding", "Keep it that way: every month without Fuliza/KCB saves you KES 1,800–4,500 in fees.")
        }

        // 3. Loan cycling
        if (m.cycling.isNotEmpty()) {
            out += Advice(
                3, "Loan cycling detected (${m.cycling.size}x this month)",
                m.cycling.joinToString("\n") { "• $it" } +
                    "\nRe-borrowing right after repaying means you pay the fee every month without ever getting ahead. Only re-borrow for a specific business deal that sells before the due date.",
            )
        }

        // 4. Fuliza habit
        if (m.fulizaDraws >= 5) {
            out += Advice(
                2, "Fuliza used ${m.fulizaDraws} times this month",
                "Fuliza is the most expensive everyday loan (1% + up to KES 30/day). Consider lowering your limit or opting out (*234# → Fuliza).",
            )
        } else if (m.fulizaDraws == 0 && now - ms > 7 * DAY_MS) {
            out += Advice(0, "No Fuliza this month 👏", "That's real money saved — keep going.")
        }

        // 5. Fees
        val fees = m.loanFees + m.mpesaCharges
        if (fees > 0) {
            val pct = if (m.income > 0) " (${String.format("%.1f", fees / m.income * 100)}% of income)" else ""
            out += Advice(
                1, "${ksh(fees)} paid in fees this month$pct",
                "Loan fees/interest: ${ksh(m.loanFees)}\nM-Pesa transaction costs: ${ksh(m.mpesaCharges)}\nLast month: ${ksh(prev.loanFees + prev.mpesaCharges)}",
            )
        }

        // 6. Weekly allowance
        val spentWeek = Analytics.weekSpent(s.txs, now)
        val daysLeft = 7 - Analytics.daysUntil(now, Analytics.weekStart(now)).coerceIn(0, 6)
        val left = weeklyAllowance - spentWeek
        out += if (left >= 0) Advice(
            if (left < weeklyAllowance * 0.2) 2 else 1,
            "Weekly allowance: ${ksh(left)} left",
            "Spent ${ksh(spentWeek)} of ${ksh(weeklyAllowance)} on food, eating out, fuel, data, going out and family. That's about ${ksh(left / daysLeft)} a day for the rest of the week.",
        ) else Advice(
            3, "Weekly allowance overspent by ${ksh(-left)}",
            "You've spent ${ksh(spentWeek)} against ${ksh(weeklyAllowance)}. Pause non-essential spending until Monday — and don't use Fuliza to fill the gap.",
        )

        // 7. Budgets
        val spentByCat = m.spendByCat.toMap()
        budgets.forEach { (cat, limit) ->
            val spent = spentByCat[cat] ?: 0.0
            if (limit <= 0 && spent > 0 && cat != Cat.CLOTHES) {
                out += Advice(2, "$cat: ${ksh(spent)} this month", "This category's budget is zero.")
            } else if (limit > 0 && spent > limit) {
                out += Advice(2, "$cat over budget", "Spent ${ksh(spent)} of ${ksh(limit)} (+${ksh(spent - limit)}).")
            } else if (limit > 0 && spent > limit * 0.8) {
                out += Advice(1, "$cat at ${(spent / limit * 100).toInt()}% of budget", "Spent ${ksh(spent)} of ${ksh(limit)}.")
            }
        }

        // 8. Subscriptions
        val subs = m.txs.filter { it.category == Cat.SUBS && it.type in Cat.spendTypes }.map { it.counterparty.substringBefore(" ") }.distinct()
        if (subs.size > 2) {
            out += Advice(1, "${subs.size} subscriptions this month", "${subs.joinToString(", ")}. Keep the two you use most.")
        }

        // 9. Income vs spending
        if (m.income > 0 && m.spending > m.income) {
            out += Advice(2, "Spending is above income this month", "In: ${ksh(m.income)} · Out: ${ksh(m.spending)}. Borrowed: ${ksh(m.borrowed)}.")
        }

        // 9b. Savings-backed loan: show the split and where it sits in the payoff order
        s.manualLoans.filter { it.second > 0 && it.first.principalPart > 0 }.forEach { (l, left) ->
            val saved = com.myfinance.app.data.Repo.lenderSavings(l, s.txs)
            val rate = if (left > 0) l.interestPart / left * 100 else 0.0
            out += Advice(
                0, "${l.lender.substringBefore(" (")}: ${ksh(left)} owed · ${ksh(saved)} saved",
                "Each ${ksh(l.monthly)} = ${ksh(l.principalPart)} loan + ${ksh(l.interestPart)} interest + ${ksh(l.savingsPart)} savings" +
                    (if (l.otherPart > 0) " + ${ksh(l.otherPart)} to your second account" else "") +
                    ". ${String.format("%.0f", (l.savingsPart + l.otherPart) / l.monthly * 100)}% of every payment is savings. " +
                    "Interest is about ${String.format("%.1f", rate)}% a month — your cheapest debt, so pay it on time but clear the others first. " +
                    "About ${kotlin.math.ceil(left / l.principalPart).toInt()} payments to go.",
                tag = "longterm",
            )
        }

        // 10. Stock funded by loans
        val loanStock = stock.filter { !it.sold && !it.fundedBy.equals("Own money", true) && it.fundedBy.isNotBlank() }
        if (loanStock.isNotEmpty()) {
            out += Advice(
                1, "${ksh(loanStock.sumOf { it.cost })} of stock funded by loans",
                "When it sells, repay the loans first, then put part of the profit into an emergency fund before buying more stock.",
                tag = "longterm",
            )
        }

        // 11. Business money mixing
        val pochiSpend = m.txs.filter { it.wallet == "Pochi" && it.type in Cat.spendTypes && it.category !in Cat.business }.sumOf { it.amount }
        if (pochiSpend > 0) {
            out += Advice(1, "${ksh(pochiSpend)} of personal spending from Pochi", "Keep Pochi for business only, so you can see if the business really makes money.")
        }

        return out.sortedByDescending { it.level }
    }

    data class DealResult(val loanCost: Double, val net: Double, val verdict: String, val good: Boolean)

    fun dealCheck(amount: Double, profit: Double, days: Int, lender: String): DealResult {
        val cost = Loans.cost(lender, amount, days)
        val net = profit - cost
        val roi = if (amount > 0) profit / amount * 100 else 0.0
        val shortTerm = lender !in setOf("Own money", "Hustler/Bridge")
        val verdict = when {
            amount <= 0 -> "Enter the amount you'd put in."
            net <= 0 -> "Not worth it: the ${lender} cost (${ksh(cost)}) eats the whole profit."
            lender == "Fuliza" -> "Avoid: Fuliza for stock is expensive and blurs business with personal money. Net ${ksh(net)}."
            lender == "ZKPesa" -> "Avoid: ZKPesa costs ~41% per loan. Net ${ksh(net)} — use a cheaper lender."
            shortTerm && days > 30 -> "Risky: the loan is due before you sell (${days} days). You'd have to repay from salary or roll over (cost included). Net ${ksh(net)}."
            else -> "Worth it: ${String.format("%.0f", roi)}% gross return, net ${ksh(net)} after a ${ksh(cost)} loan cost. Repay from the sale before reinvesting."
        }
        return DealResult(cost, net, verdict, net > 0 && lender !in setOf("Fuliza", "ZKPesa") && !(shortTerm && days > 30))
    }
}
