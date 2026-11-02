package com.myfinance.app.logic

import com.myfinance.app.data.Cat
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val DAY_MS = 86_400_000L

data class MonthSummary(
    val start: Long,
    val end: Long,
    val txs: List<Tx>,
    val income: Double,     // earned: salary, sales, money from people (not loans or debts repaid to you)
    val moneyIn: Double,    // everything that came in
    val spending: Double,   // excludes savings and money lent out
    val lent: Double,
    val repaidToMe: Double,
    val incomeByCat: List<Pair<String, Double>>,
    val spendByCat: List<Pair<String, Double>>,
    val borrowed: Double,
    val repaid: Double,
    val loanFees: Double,
    val mpesaCharges: Double,
    val fulizaDraws: Int,
    val cycling: List<String>,
)

object Analytics {
    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** [offset] 0 = this month, -1 = last month... Returns [start, end). */
    fun monthRange(offset: Int, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = startOfDay(now); set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset)
        }
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to c.timeInMillis
    }

    fun weekStart(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(now)
        firstDayOfWeek = Calendar.MONDAY
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    fun month(all: List<Tx>, start: Long, end: Long): MonthSummary {
        val txs = all.filter { it.time in start until end }
        val inc = txs.filter { (it.type == TxType.RECEIVED || (it.type == TxType.DEPOSIT && it.category.isNotEmpty())) && it.category != Cat.TRANSFER }
        val spend = txs.filter { it.type in Cat.spendTypes && it.category !in Cat.notSpending }
        fun byCat(l: List<Tx>) = l.groupBy { it.category }.map { it.key to it.value.sumOf { t -> t.amount } }.sortedByDescending { it.second }
        return MonthSummary(
            start, end, txs,
            income = inc.filter { it.category !in Cat.notIncome }.sumOf { it.amount },
            moneyIn = inc.sumOf { it.amount },
            spending = spend.sumOf { it.amount },
            lent = txs.filter { it.category == Cat.LENT }.sumOf { it.amount },
            repaidToMe = inc.filter { it.category == Cat.REPAID_TO_ME }.sumOf { it.amount },
            incomeByCat = byCat(inc),
            spendByCat = byCat(spend),
            borrowed = txs.filter { it.type == TxType.LOAN_IN || it.category == Cat.BORROWED }.sumOf { it.amount },
            repaid = txs.filter { it.type == TxType.LOAN_REPAY }.sumOf { it.amount },
            loanFees = txs.filter { it.type == TxType.FEE }.sumOf { it.amount },
            mpesaCharges = txs.sumOf { it.fee },
            fulizaDraws = txs.count { it.type == TxType.LOAN_IN && it.counterparty == "Fuliza" },
            cycling = cycling(all, start, end),
        )
    }

    /** Loans repaid and then borrowed again from the same lender within 48 hours (Fuliza excluded). */
    fun cycling(all: List<Tx>, start: Long, end: Long): List<String> {
        val ins = all.filter { it.type == TxType.LOAN_IN && it.counterparty != "Fuliza" }
        return all.filter { it.type == TxType.LOAN_REPAY && it.counterparty != "Fuliza" && it.time in start until end && it.amount > 100 }
            .mapNotNull { r ->
                ins.firstOrNull { it.counterparty == r.counterparty && it.time >= r.time && it.time - r.time < 2 * DAY_MS }
                    ?.let { "${r.counterparty}: repaid ${ksh(r.amount)} then re-borrowed ${ksh(it.amount)} on ${fmt(it.time)}" }
            }.distinct()
    }

    fun weekSpent(all: List<Tx>, now: Long = System.currentTimeMillis()): Double {
        val ws = weekStart(now)
        return all.filter { it.time >= ws && it.time < ws + 7 * DAY_MS && it.type in Cat.spendTypes && it.category in Cat.weekly }.sumOf { it.amount }
    }

    /** How many instalments of [l] have been paid since [ManualLoan.asOf] (payments of at least half the monthly amount). */
    fun instalmentsPaid(l: ManualLoan, txs: List<Tx>): List<Tx> =
        if (l.matchName.isBlank()) emptyList()
        else txs.filter {
            it.time > l.asOf && (it.category == Cat.LOANPAY || it.category == Cat.FAMILY) && it.type in Cat.spendTypes &&
                it.counterparty.contains(l.matchName, ignoreCase = true) && it.amount >= l.monthly * 0.5
        }

    fun daysUntil(t: Long, now: Long = System.currentTimeMillis()): Int =
        ((startOfDay(t) - startOfDay(now)) / DAY_MS).toInt()

    /** Next due date of a manual loan's monthly instalment. */
    fun nextDue(l: ManualLoan, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(now) }
        val dom = l.dueDay.coerceIn(1, 28)
        if (c.get(Calendar.DAY_OF_MONTH) > dom) c.add(Calendar.MONTH, 1)
        c.set(Calendar.DAY_OF_MONTH, dom)
        return c.timeInMillis
    }
}

fun ksh(d: Double): String = "KES " + String.format(Locale.US, "%,.0f", d)
fun fmt(t: Long, p: String = "d MMM"): String = SimpleDateFormat(p, Locale.ENGLISH).format(Date(t))
