package com.myfinance.app.data

import com.myfinance.app.model.LoanEvent
import com.myfinance.app.model.LoanOp
import com.myfinance.app.model.LoanStatus
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import kotlin.math.max

object Loans {
    /**
     * Replays lender events in time order to get each lender's current balance and due date.
     * Also returns fee transactions that can only be inferred from balance changes
     * (Fuliza daily charges, interest on lenders without balance SMS).
     */
    fun compute(events: List<LoanEvent>): Pair<List<LoanStatus>, List<Tx>> {
        class S { var amt = 0.0; var due: Long? = null; var last = 0L }
        val state = LinkedHashMap<String, S>()
        val fees = ArrayList<Tx>()
        for (e in events.sortedBy { it.time }) {
            val s = state.getOrPut(e.lender) { S() }
            when (e.op) {
                LoanOp.SET -> { s.amt = e.amount; if (e.due != null) s.due = e.due }
                LoanOp.ADD -> s.amt += e.amount
                LoanOp.REDUCE -> {
                    val excess = e.amount - s.amt
                    if (e.lender in NO_STATUS_LENDERS && excess > 0.5) {
                        fees.add(Tx("INT-${e.lender}-${e.time}", e.time, TxType.FEE, round2(excess), "${e.lender} interest"))
                    }
                    s.amt = max(0.0, s.amt - e.amount)
                }
                LoanOp.CLEAR -> { s.amt = 0.0; s.due = null }
                LoanOp.DUE -> s.due = e.due
                LoanOp.FULIZA_DRAW -> {
                    val daily = e.amount - s.amt - e.draw - e.fee
                    if (s.amt > 0 && daily > 0.01 && daily < 1000) {
                        fees.add(Tx("FZD-${e.time}", e.time, TxType.FEE, round2(daily), "Fuliza daily charges"))
                    }
                    s.amt = e.amount
                    s.due = e.due
                }
            }
            s.last = e.time
        }
        val statuses = state.filter { it.value.amt > 0.5 }
            .map { LoanStatus(it.key, round2(it.value.amt), it.value.due, it.value.last) }
            .sortedBy { it.due ?: Long.MAX_VALUE }
        return statuses to fees
    }

    /** Approximate cost of borrowing [amount] for [days] from [lender], based on observed charges. */
    fun cost(lender: String, amount: Double, days: Int): Double {
        val periods30 = max(1, (days + 29) / 30)
        return when (lender) {
            "Own money" -> 0.0
            "Hustler/Bridge" -> amount * 0.008 * periods30
            "KCB M-PESA" -> amount * 0.0879 * periods30
            "M-Shwari" -> amount * 0.09 * periods30
            "Equity" -> amount * 0.08 * periods30
            "ZKPesa" -> amount * 0.415 * max(1, (days + 13) / 14)
            "Fuliza" -> amount * 0.01 + fulizaDaily(amount) * days
            else -> amount * 0.10 * periods30
        }
    }

    fun fulizaDaily(outstanding: Double): Double = when {
        outstanding <= 100 -> 0.0
        outstanding <= 500 -> 3.0
        outstanding <= 1000 -> 6.0
        outstanding <= 1500 -> 18.0
        outstanding <= 2500 -> 20.0
        else -> 30.0
    }

    /** Cheapest first: the order in which it's least bad to hold debt (pay the others first). */
    val COST_RANK = listOf("ZKPesa", "Zenka", "Timiza", "Fuliza", "M-Shwari", "KCB M-PESA", "Equity", "Hustler/Bridge")

    val DEAL_LENDERS = listOf("Own money", "Hustler/Bridge", "KCB M-PESA", "M-Shwari", "Fuliza", "ZKPesa")
}
