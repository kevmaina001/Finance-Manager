package com.myfinance.app.data

import com.myfinance.app.model.LoanEvent
import com.myfinance.app.model.LoanOp
import com.myfinance.app.model.ParseResult
import com.myfinance.app.model.Sms
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

private const val A = "([\\d,]+(?:\\.\\d{1,2})?)"
private const val D = "(\\d{1,2}[/-](?:\\d{1,2}|[A-Za-z]{3})[/-]\\d{2,4})"
private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

fun amt(s: String): Double = s.replace(",", "").toDoubleOrNull() ?: 0.0
fun round2(d: Double) = round(d * 100) / 100

private val DMY4 = Regex("\\d{1,2}/\\d{1,2}/\\d{4}")
private val DMY2 = Regex("\\d{1,2}/\\d{1,2}/\\d{2}")
private val DMON4 = Regex("\\d{1,2}-[A-Za-z]{3}-\\d{4}")
private val DMON2 = Regex("\\d{1,2}-[A-Za-z]{3}-\\d{2}")

/** Parses due dates as written in Kenyan lender SMS: 06/11/2026, 29/10/26, 02-NOV-26, 02-Nov-2026. */
fun parseDate(s: String): Long? {
    val t = s.trim()
    val f = when {
        DMY4.matches(t) -> "d/M/yyyy"
        DMY2.matches(t) -> "d/M/yy"
        DMON4.matches(t) -> "d-MMM-yyyy"
        DMON2.matches(t) -> "d-MMM-yy"
        else -> return null
    }
    return try {
        SimpleDateFormat(f, Locale.ENGLISH).apply { isLenient = false }.parse(t)?.time
    } catch (e: Exception) {
        null
    }
}

/** "JANE DOE 0712***345" -> "JANE DOE", "Equity Bulk Account 300600" -> "EQUITY BULK ACCOUNT" */
fun cleanName(s: String): String {
    var n = s.uppercase()
    n = n.replace(Regex("\\s+(?:0|\\+?254)[\\d*]{6,}.*$"), "")
    n = n.replace(Regex("[.\\s]+$"), "")
    n = n.replace(Regex("(\\s+\\d+)+$"), "")
    return n.replace(Regex("\\s+"), " ").trim()
}

private val BRIDGE_RX = Regex("\\bBRIDGE\\b|HUSTLER")

fun lenderOf(name: String): String? {
    val u = name.uppercase()
    return when {
        BRIDGE_RX.containsMatchIn(u) -> "Hustler/Bridge"
        u.contains("CHAPEO") || u.contains("ZKPESA") -> "ZKPesa"
        u.contains("ZENKA") -> "Zenka"
        u.contains("TIMIZA") -> "Timiza"
        u.contains("KCB M-PESA") -> "KCB M-PESA"
        u.contains("M-SHWARI") -> "M-Shwari"
        else -> null
    }
}

/** Lenders that don't send their own balance SMS: we track them from money in/out. */
val NO_STATUS_LENDERS = setOf("ZKPesa", "Zenka", "Timiza")

/** [ownAccounts]: paybill account numbers that belong to the user (e.g. their Equity account). */
class Parser(ownerName: String, ownAccounts: Set<String> = emptySet()) {
    private val ownAccts = ownAccounts.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()

    private val ownerTokens = ownerName.uppercase().split(Regex("\\s+")).filter { it.length > 1 }.take(2)

    fun isOwner(n: String): Boolean {
        val u = n.uppercase()
        return ownerTokens.isNotEmpty() && ownerTokens.all { u.contains(it) }
    }

    private val refName = HashMap<String, String>()
    private val ownRefs = HashSet<String>()
    private data class Note(val time: Long, val amount: Double, val name: String)
    private val notes = ArrayList<Note>()
    private val txs = ArrayList<Tx>()
    private val events = ArrayList<LoanEvent>()
    private var mpesaBal: Pair<Long, Double>? = null
    private var pochiBal: Pair<Long, Double>? = null

    fun parse(all: List<Sms>): ParseResult {
        val sorted = all.sortedBy { it.time }
        sorted.forEach { bankPass(it) }
        // a paybill account the bank confirmed as yours ("deposited ... in favor of <you>") stays yours,
        // so later transfers to it count as own transfers even when the bank sends no SMS
        sorted.filter { it.address.equals("MPESA", true) }.forEach { s ->
            val ref = RECEIPT.find(s.body)?.groupValues?.get(1) ?: return@forEach
            if (ref in ownRefs) PAYBILL.find(s.body)?.let { ownAccts.add(it.groupValues[3].trim()) }
        }
        sorted.forEach {
            if (it.address.equals("MPESA", true)) mpesa(it) else loanSms(it)
        }
        return ParseResult(txs.distinctBy { it.id }, events.sortedBy { it.time }, mpesaBal, pochiBal)
    }

    // ---------------------------------------------------------------- banks
    private val EQ_CREDITED = rx("Kshs\\.?\\s*$A has been credited to \\S+ (.+?)\\. Ref\\. \\S+?\\.? MPESA Ref\\. (\\w+)")
    private val EQ_SENT_YOU = rx("^(.+?) has sent KShs\\.?\\s*$A to your MPESA\\. The MPESA receipt number is (\\w+)")
    private val EQ_DEPOSITED = rx("KES\\.?\\s*$A has successfully been deposited to Equity Account in favor of (.+?) Ref\\. Number (\\w+)")
    private val EQ_FROM_MPESA = rx("KES\\.?\\s*$A from (.+?) received via M-?Pesa Ref\\. (\\w+)")
    private val EQ_CLEARED = rx("KES\\s*$A has been successfully paid to clear your loan")
    private val EQ_DUE = rx("your loan of Ksh\\s*$A is due (?:in \\d+ days |on )?$D")
    private val EQ_DUE_TODAY = rx("your loan of Ksh\\s*$A is due today")
    private val EQ_APPROVED = rx("Repayment amount of KES\\s*$A will be due on $D")
    private val IM_RECEIVED = rx("received KES\\s*$A from (.+?)\\. Transaction Ref.*?Mpesa Ref ID: (\\w+)")
    private val IM_PAID = rx("KES\\s*$A paid to (.+?), .*?Ref: (\\w+)")
    private val COOP_RECEIVED = rx("received KES\\s*$A on your M-Pesa from (.+?)-[\\d*]+ at .*?receipt number is:\\s*(\\w+)")
    private val KCB_RECEIVED = rx("(?:KES\\s*$A received from|received KES\\s*$A from) (.+?) - [\\d*]")
    private val KCB_ACCOUNT = rx("sent to KCB account (.+?) \\d+ has been received.*?M-PESA Ref (\\w+)")
    private val KCB_PAYBILL = rx("sent to KCB Pay ?Bill \\d+ for account \\S+ (.+?) has been received.*?M-PESA ref (\\w+)")
    private val STANBIC_RECEIVED = rx("received KES\\s*$A into your MPESA account from (.+?) through")
    private val ABSA_DEPOSIT = rx("deposited KES\\s*$A MPESA ref: (\\w+) to account \\S+ for (.+?) through")

    private fun bankPass(s: Sms) {
        val a = s.address.uppercase()
        val b = s.body
        val t = s.time
        when {
            a.startsWith("EQUITY") -> {
                EQ_CREDITED.find(b)?.let { m ->
                    val (am, name, ref) = m.destructured
                    if (isOwner(name)) ownRefs.add(ref)
                    else txs.add(Tx("EQ-$ref", t, TxType.BANK_SENT, amt(am), cleanName(name), wallet = "Equity", ref = ref, raw = b))
                }
                EQ_SENT_YOU.find(b)?.let { m -> refName[m.groupValues[3]] = cleanName(m.groupValues[1]) }
                EQ_DEPOSITED.find(b)?.let { m ->
                    val (_, name, ref) = m.destructured
                    if (isOwner(name)) ownRefs.add(ref) else refName[ref] = cleanName(name)
                }
                EQ_FROM_MPESA.find(b)?.let { m ->
                    val (_, name, ref) = m.destructured
                    if (isOwner(name)) ownRefs.add(ref)
                }
                EQ_CLEARED.find(b)?.let { m ->
                    txs.add(Tx("EQL-$t", t, TxType.LOAN_REPAY, amt(m.groupValues[1]), "Equity", wallet = "Equity", raw = b))
                    events.add(LoanEvent(t, "Equity", LoanOp.CLEAR))
                }
                EQ_DUE.find(b)?.let { m -> events.add(LoanEvent(t, "Equity", LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2]))) }
                EQ_DUE_TODAY.find(b)?.let { m -> events.add(LoanEvent(t, "Equity", LoanOp.SET, amt(m.groupValues[1]), t)) }
                EQ_APPROVED.find(b)?.let { m -> events.add(LoanEvent(t, "Equity", LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2]))) }
            }
            a.startsWith("IANDM") -> {
                IM_RECEIVED.find(b)?.let { m -> refName[m.groupValues[3]] = cleanName(m.groupValues[2]) }
                IM_PAID.find(b)?.let { m -> refName[m.groupValues[3]] = cleanName(m.groupValues[2].substringBefore(",")) }
            }
            a.startsWith("COOP") -> COOP_RECEIVED.find(b)?.let { m -> refName[m.groupValues[3]] = cleanName(m.groupValues[2]) }
            a == "KCB" -> {
                KCB_RECEIVED.find(b)?.let { m ->
                    val am = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    notes.add(Note(t, amt(am), cleanName(m.groupValues[3])))
                }
                KCB_ACCOUNT.find(b)?.let { m -> refName[m.groupValues[2]] = cleanName(m.groupValues[1]) }
                KCB_PAYBILL.find(b)?.let { m -> refName[m.groupValues[2]] = cleanName(m.groupValues[1]) }
            }
            a.startsWith("STANBIC") -> STANBIC_RECEIVED.find(b)?.let { m -> notes.add(Note(t, amt(m.groupValues[1]), cleanName(m.groupValues[2]))) }
            a.startsWith("ABSA") -> ABSA_DEPOSIT.find(b)?.let { m -> refName[m.groupValues[2]] = cleanName(m.groupValues[3]) }
        }
    }

    private fun matchNote(t: Long, amount: Double): String? {
        val n = notes.firstOrNull { abs(it.time - t) < 15 * 60_000 && abs(it.amount - amount) < 0.01 } ?: return null
        notes.remove(n)
        return n.name
    }

    // ---------------------------------------------------------------- M-Pesa
    private val RECEIPT = Regex("^([A-Z0-9]{10}) ")
    private val MPESA_BAL = rx("M-?PESA balance (?:is )?(?:Ksh ?)?$A")
    private val POCHI_BAL = rx("Pochi balance is Ksh ?$A")
    private val COST = rx("Transaction cost,? Ksh ?$A")
    private val FZ_DRAW = rx("Fuliza M-PESA amount is Ksh ?$A\\. Access Fee charged Ksh ?$A\\. Total Fuliza M-PESA outstanding amount is Ksh ?$A due on $D")
    private val FZ_REPAY = rx("Ksh ?$A from your M-PESA has been used to (fully|partially) pay your outstanding Fuliza")
    private val MOVED = rx("has been moved from your (?:Pochi|M-PESA) account")
    private val MSHWARI_LOAN = rx("M-Shwari loan has been approved.*?Ksh ?$A less excise")
    private val MSHWARI_XFER = rx("transferred (?:to|from) M-Shwari")
    private val LOAN_REPAY = rx("loan repayment of Ksh ?$A from your M-PESA account to (.+?) on")
    private val KCB_XFER = rx("transfer+ed Ksh ?$A from your KCB M-PESA")
    private val RECEIVED = rx("received Ksh ?$A from (.+?)(?:\\s+(?:0|\\+?254)[\\d*]{6,}|\\.? on \\d)")
    private val SENT_TO = rx("You have sent Ksh ?$A to (.+?)\\.? on")
    private val PAYBILL = rx("Ksh ?$A sent to (.+?) for account (.+?) on \\d")
    private val SEND = rx("Ksh ?$A sent to (.+?)(?:\\s+(?:0|\\+?254)\\d{8,9})?\\s+on \\d")
    private val TILL = rx("Ksh ?$A paid to (.+?)\\.?\\s+on \\d")
    private val WITHDRAW = rx("Withdraw Ksh ?$A from (.+?)\\s+New")
    private val DEPOSIT = rx("Give Ksh ?$A cash to (.+?)\\s+New")
    private val AIRTIME = rx("bought Ksh ?$A of airtime")

    private fun mpesa(s: Sms) {
        val b = s.body
        val t = s.time
        if (b.startsWith("Failed", true) || b.contains("unable to process", true)) return
        val ref = RECEIPT.find(b)?.groupValues?.get(1) ?: "T$t"

        MPESA_BAL.findAll(b).lastOrNull()?.let { mpesaBal = t to amt(it.groupValues[1]) }
        POCHI_BAL.find(b)?.let { pochiBal = t to amt(it.groupValues[1]) }

        val wallet = if (b.contains("Pochi balance", true) && !b.contains("M-PESA balance", true)) "Pochi" else "M-PESA"
        val cost = COST.find(b)?.let { amt(it.groupValues[1]) } ?: 0.0

        fun add(type: TxType, a: Double, cp: String, acct: String = "", suffix: String = "", fee: Double = cost) {
            txs.add(Tx(ref + suffix, t, type, a, cp, acct, fee, wallet, ref, b))
        }

        FZ_DRAW.find(b)?.let { m ->
            val draw = amt(m.groupValues[1]); val fee = amt(m.groupValues[2]); val total = amt(m.groupValues[3])
            add(TxType.LOAN_IN, draw, "Fuliza", suffix = "-FZ", fee = 0.0)
            add(TxType.FEE, fee, "Fuliza access fee", suffix = "-FZF", fee = 0.0)
            events.add(LoanEvent(t, "Fuliza", LoanOp.FULIZA_DRAW, total, parseDate(m.groupValues[4]), draw, fee))
            return
        }
        FZ_REPAY.find(b)?.let { m ->
            val a = amt(m.groupValues[1])
            add(TxType.LOAN_REPAY, a, "Fuliza", suffix = "-FZR", fee = 0.0)
            events.add(
                if (m.groupValues[2].equals("fully", true)) LoanEvent(t, "Fuliza", LoanOp.CLEAR)
                else LoanEvent(t, "Fuliza", LoanOp.REDUCE, a)
            )
            return
        }
        if (MOVED.containsMatchIn(b) || MSHWARI_XFER.containsMatchIn(b)) return
        if (b.contains("loan request will be processed", true)) return
        MSHWARI_LOAN.find(b)?.let { m -> add(TxType.LOAN_IN, amt(m.groupValues[1]), "M-Shwari", fee = 0.0); return }
        LOAN_REPAY.find(b)?.let { m ->
            val lender = lenderOf(m.groupValues[2]) ?: cleanName(m.groupValues[2])
            add(TxType.LOAN_REPAY, amt(m.groupValues[1]), lender)
            return
        }
        KCB_XFER.find(b)?.let { m -> add(TxType.INTERNAL, amt(m.groupValues[1]), "KCB M-PESA account"); return }

        RECEIVED.find(b)?.let { m ->
            val a = amt(m.groupValues[1]); val name = m.groupValues[2]
            val lender = lenderOf(name)
            if (lender != null) {
                add(TxType.LOAN_IN, a, lender)
                if (lender in NO_STATUS_LENDERS) events.add(LoanEvent(t, lender, LoanOp.ADD, a))
                return
            }
            val mapped = refName[ref] ?: matchNote(t, a)
            if (ref in ownRefs || (mapped != null && isOwner(mapped))) add(TxType.INTERNAL, a, "Own transfer (${cleanName(name)})")
            else add(TxType.RECEIVED, a, mapped ?: cleanName(name), acct = cleanName(name))
            return
        }
        SENT_TO.find(b)?.let { m ->
            val lender = lenderOf(m.groupValues[2])
            if (lender != null) {
                add(TxType.LOAN_REPAY, amt(m.groupValues[1]), lender)
                if (lender in NO_STATUS_LENDERS) events.add(LoanEvent(t, lender, LoanOp.REDUCE, amt(m.groupValues[1])))
            } else add(TxType.SENT, amt(m.groupValues[1]), cleanName(m.groupValues[2]))
            return
        }
        PAYBILL.find(b)?.let { m ->
            val a = amt(m.groupValues[1]); val to = m.groupValues[2].trim(); val acct = m.groupValues[3].trim()
            val lender = lenderOf(to)
            when {
                to.contains("M-PESA CARD", true) -> add(TxType.CARD, a, cleanName(acct), acct = "M-PESA CARD")
                ref in ownRefs || acct in ownAccts -> add(TxType.INTERNAL, a, "Own transfer (${cleanName(to)})", acct)
                lender != null -> {
                    add(TxType.LOAN_REPAY, a, lender, acct)
                    if (lender in NO_STATUS_LENDERS) events.add(LoanEvent(t, lender, LoanOp.REDUCE, a))
                }
                else -> add(TxType.PAYBILL, a, refName[ref] ?: cleanName(to), acct)
            }
            return
        }
        SEND.find(b)?.let { m -> add(TxType.SENT, amt(m.groupValues[1]), refName[ref] ?: cleanName(m.groupValues[2])); return }
        TILL.find(b)?.let { m -> add(TxType.TILL, amt(m.groupValues[1]), cleanName(m.groupValues[2])); return }
        WITHDRAW.find(b)?.let { m -> add(TxType.WITHDRAW, amt(m.groupValues[1]), cleanName(m.groupValues[2])); return }
        DEPOSIT.find(b)?.let { m -> add(TxType.DEPOSIT, amt(m.groupValues[1]), cleanName(m.groupValues[2])); return }
        AIRTIME.find(b)?.let { m -> add(TxType.PAYBILL, amt(m.groupValues[1]), "AIRTIME"); return }
    }

    // ---------------------------------------------------------------- lenders
    private val KCB_NEW = rx("loan of KES\\s*$A has been processed.*?repayment of KES\\s*$A is due on $D")
    private val KCB_DUE = rx("loan repayment of KES\\s*$A is due on $D")
    private val KCB_BAL = rx("loan balance of KES\\s*$A is due on $D")
    private val BR_NEW = rx("Loan amount is KES\\s*$A\\s*\\.?\\s*Due date is $D")
    private val BR_OVERDUE = rx("overdue Bridge loan product of KES\\.?\\s*$A that was due on $D")
    private val BR_OUT = rx("outstanding Bridge loan product of KES\\.?\\s*$A")
    private val BR_HF = rx("Hustler Fund personal Loan of Ksh\\.?\\s*$A")
    private val BR_CLEAR = rx("successfully paid your Bridge loan|has been fully paid")
    private val MS_BAL = rx("total loan balance of Ksh\\.?\\s*$A is due on $D")
    private val MS_FEE = rx("Facility fees charged is Ksh\\.?\\s*$A and excise duty of Ksh\\.?\\s*$A")
    private val MS_DUE = rx("loan of Kshs?\\.?\\s*$A is due on $D")
    private val MS_REPAY = rx("Repay your M-Shwari loan of KES\\s*$A")
    private val MS_CLEAR = rx("fully repaid|has been repaid|repaid in full")
    private val FZ_OUT = rx("outstanding Fuliza of Ksh\\.?\\s*$A as at .*? is due on $D")

    private fun loanSms(s: Sms) {
        val a = s.address.uppercase()
        val b = s.body
        val t = s.time
        fun ev(l: String, op: LoanOp, x: Double = 0.0, due: Long? = null) = events.add(LoanEvent(t, l, op, x, due))
        when {
            a == "KCB M-PESA" -> {
                KCB_NEW.find(b)?.let { m ->
                    val p = amt(m.groupValues[1]); val rep = amt(m.groupValues[2])
                    txs.add(Tx("KCBL-$t", t, TxType.LOAN_IN, p, "KCB M-PESA", wallet = "KCB M-PESA", raw = b))
                    if (rep > p) txs.add(Tx("KCBF-$t", t, TxType.FEE, round2(rep - p), "KCB M-PESA loan fee", raw = b))
                    ev("KCB M-PESA", LoanOp.SET, rep, parseDate(m.groupValues[3]))
                    return
                }
                KCB_DUE.find(b)?.let { m -> ev("KCB M-PESA", LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                KCB_BAL.find(b)?.let { m -> ev("KCB M-PESA", LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                if (b.contains("fully paid", true)) ev("KCB M-PESA", LoanOp.CLEAR)
            }
            a.startsWith("BRIDGE") || a.startsWith("HUSTLER") -> {
                val l = "Hustler/Bridge"
                BR_NEW.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                BR_OVERDUE.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                BR_OUT.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1])); return }
                BR_HF.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1])); return }
                if (BR_CLEAR.containsMatchIn(b)) ev(l, LoanOp.CLEAR)
            }
            a == "M-SHWARI" -> {
                val l = "M-Shwari"
                MS_FEE.find(b)?.let { m ->
                    txs.add(Tx("MSF-$t", t, TxType.FEE, amt(m.groupValues[1]) + amt(m.groupValues[2]), "M-Shwari loan fee", raw = b))
                }
                MS_BAL.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                MS_DUE.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])); return }
                MS_REPAY.find(b)?.let { m -> ev(l, LoanOp.SET, amt(m.groupValues[1])); return }
                if (MS_CLEAR.containsMatchIn(b)) ev(l, LoanOp.CLEAR)
            }
            a == "FULIZA" -> FZ_OUT.find(b)?.let { m -> ev("Fuliza", LoanOp.SET, amt(m.groupValues[1]), parseDate(m.groupValues[2])) }
            a.startsWith("CHAPEO") || a.contains("ZKPESA") -> {
                val l = "ZKPesa"
                when {
                    b.contains("paid in full", true) -> ev(l, LoanOp.CLEAR)
                    b.contains("due tomorrow", true) -> ev(l, LoanOp.DUE, due = t + 86_400_000L)
                    b.contains("due today", true) -> ev(l, LoanOp.DUE, due = t)
                }
            }
        }
    }
}
