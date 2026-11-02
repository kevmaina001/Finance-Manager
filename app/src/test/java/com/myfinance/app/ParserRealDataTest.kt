package com.myfinance.app

import com.myfinance.app.data.Repo
import com.myfinance.app.logic.Analytics
import com.myfinance.app.logic.Advisor
import com.myfinance.app.logic.ksh
import com.myfinance.app.data.Store
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.Sms
import com.myfinance.app.model.TxType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * Runs the parser over a real `adb shell content query` SMS dump and prints what it found.
 * Skipped unless SMS_FIXTURES points at one or more dump files (separated by ';'); OWNER_NAME is your name as in bank SMS.
 * The dumps are personal data and are never stored in the project.
 */
class ParserRealDataTest {
    private fun load(path: String): List<Sms> {
        val raw = File(path).readText()
        val ws = Regex("\\s+")
        return raw.split(Regex("(?m)^Row: \\d+ ")).mapNotNull { r ->
            val m = Regex("date=(\\d+), address=(.*?), body=(.*)", RegexOption.DOT_MATCHES_ALL).find(r) ?: return@mapNotNull null
            val addr = m.groupValues[2].trim()
            if (addr.startsWith("+") || addr.firstOrNull()?.isDigit() == true) null
            else Sms(m.groupValues[1].toLong(), addr, m.groupValues[3].replace(ws, " ").trim())
        }
    }

    @Test
    fun summarize() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Africa/Nairobi"))
        val env = System.getenv("SMS_FIXTURES")
        assumeTrue(env != null)
        val sms = env!!.split(";").flatMap { load(it) }.distinctBy { it.time to it.body }
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 0) }.timeInMillis
        val s = Repo.build(sms, null, now, System.getenv("OWNER_NAME") ?: "")
        s.receivables.forEach { println("  owed to me: ${it.first.person} ${it.second}") }

        for (off in listOf(-2, -1, 0)) {
            val (a, b) = Analytics.monthRange(off, now)
            val m = Analytics.month(s.txs, a, b)
            println("\n===== month offset $off  txs=${m.txs.size}")
            println("income ${ksh(m.income)}  spending ${ksh(m.spending)}  borrowed ${ksh(m.borrowed)}  repaid ${ksh(m.repaid)}")
            println("loan fees ${ksh(m.loanFees)}  mpesa charges ${ksh(m.mpesaCharges)}  fuliza draws ${m.fulizaDraws}")
            println("INCOME: " + m.incomeByCat.joinToString { "${it.first}=${ksh(it.second)}" })
            println("SPEND:  " + m.spendByCat.joinToString { "${it.first}=${ksh(it.second)}" })
            m.txs.filter { it.category == "Other spending" && it.amount >= 2000 }.forEach { println("  other: ${ksh(it.amount)} ${it.type} ${it.counterparty} | ${it.account}") }
            m.cycling.forEach { println("  cycle: $it") }
            m.txs.filter { it.type == TxType.FEE }.groupBy { it.counterparty }.forEach { (k, v) -> println("  fee $k ${ksh(v.sumOf { it.amount })}") }
        }
        println("\nLOANS:")
        s.loans.forEach { println("  ${it.lender} ${it.outstanding} due=${it.due?.let { d -> java.util.Date(d) }}") }
        s.manualLoans.forEach { println("  manual ${it.first.lender} remaining ${it.second}") }
        println("M-Pesa bal ${s.mpesaBalance?.second}  Pochi ${s.pochiBalance?.second}")
        val (oa, ob) = Analytics.monthRange(-1, now)
        println("\nOct biggest RECEIVED/SENT:")
        s.txs.filter { it.time in oa until ob && it.type in setOf(TxType.RECEIVED, TxType.SENT, TxType.BANK_SENT, TxType.PAYBILL) && it.amount >= 5000 }
            .sortedBy { it.time }.forEach { println("  ${it.type} ${ksh(it.amount)} ${it.counterparty} [${it.category}] ${it.wallet}") }
        println("\nADVICE (as of 1 Nov):")
        Advisor.advise(s, Store.DEFAULT_BUDGETS, 4700.0, emptyList(), now).forEach { println("  [${it.level}] ${it.title}\n      ${it.body.replace("\n", "\n      ")}") }

        for ((mo, d) in listOf(Calendar.OCTOBER to 3, Calendar.OCTOBER to 28, Calendar.OCTOBER to 31, Calendar.NOVEMBER to 1)) {
            val t = Calendar.getInstance().apply { set(2026, mo, d, 23, 0, 0) }.timeInMillis
            val (title, text) = com.myfinance.app.work.DailySummary.build(s, 4700.0, t)
            println("\nDAILY SUMMARY ${java.util.Date(t)}\n  $title\n  ${text.replace("\n", "\n  ")}")
        }
    }
}
