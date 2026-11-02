package com.myfinance.app.data

import android.content.Context
import android.net.Uri
import com.myfinance.app.model.LoanStatus
import com.myfinance.app.logic.Analytics
import com.myfinance.app.model.BankAccount
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.PendingPay
import com.myfinance.app.model.Receivable
import com.myfinance.app.model.Sms
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import kotlin.math.abs
import kotlin.math.max

object SmsReader {
    /** Reads inbox SMS since [since], skipping personal numbers (we only want businesses/banks). */
    fun read(ctx: Context, since: Long): List<Sms> {
        val out = ArrayList<Sms>()
        val ws = Regex("\\s+")
        ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("date", "address", "body"),
            "date>=?", arrayOf(since.toString()), "date ASC",
        )?.use { c ->
            val d = c.getColumnIndexOrThrow("date")
            val a = c.getColumnIndexOrThrow("address")
            val b = c.getColumnIndexOrThrow("body")
            while (c.moveToNext()) {
                val addr = c.getString(a)?.trim() ?: continue
                if (addr.startsWith("+") || addr.firstOrNull()?.isDigit() == true) continue
                out.add(Sms(c.getLong(d), addr, (c.getString(b) ?: "").replace(ws, " ").trim()))
            }
        }
        return out
    }
}

data class Snapshot(
    val rawTxs: List<Tx>,
    val txs: List<Tx>,
    val loans: List<LoanStatus>,
    val manualLoans: List<Pair<ManualLoan, Double>>, // loan -> remaining balance
    val mpesaBalance: Pair<Long, Double>?,
    val pochiBalance: Pair<Long, Double>?,
    val loadedAt: Long,
    val receivables: List<Pair<Receivable, Double>> = emptyList(), // who owes you -> still outstanding
    val pending: List<Pair<PendingPay, Double>> = emptyList(),      // side-gig pay not received yet -> still outstanding
    val banks: List<Pair<BankAccount, Double>> = emptyList(),       // bank account -> current balance
) {
    /** Money you can use right now: M-Pesa + Pochi + bank accounts. */
    val cash get() = (mpesaBalance?.second ?: 0.0) + (pochiBalance?.second ?: 0.0) + banks.sumOf { it.second }
}

object Repo {
    private const val DAY = 86_400_000L

    fun load(ctx: Context, store: Store, days: Int = 200): Snapshot {
        val now = System.currentTimeMillis()
        return build(SmsReader.read(ctx, now - days * DAY), store, now)
    }

    fun build(sms: List<Sms>, store: Store?, now: Long = System.currentTimeMillis(), owner: String? = null): Snapshot {
        val pr = Parser(owner ?: store?.ownerName ?: Store.DEFAULT_OWNER, store?.ownAccounts ?: emptySet()).parse(sms)
        val (statuses, inferredFees) = Loans.compute(pr.loanEvents)
        val raw = (pr.txs + inferredFees).sortedByDescending { it.time }
        val s = Snapshot(raw, raw, statuses, emptyList(), pr.mpesaBalance, pr.pochiBalance, now)
        return if (store != null) rebuild(s, store) else rebuild(s, emptyMap(), emptyMap(), emptyList())
    }

    fun rebuild(s: Snapshot, store: Store) =
        rebuild(s, store.userRules, store.txOverrides, store.manualLoans, store.rides, store.receivables, store.gigTags, store.gigSenders, store.pendingPays, store.keywordRules)
            .let { r -> r.copy(banks = store.banks.map { it to bankBalance(it, r.txs) }) }

    /** Balance set by the user, then followed from SMS (only movements after [BankAccount.asOf]). */
    fun bankBalance(b: BankAccount, txs: List<Tx>): Double {
        val after = txs.filter { it.time > b.asOf }
        val word = b.name.uppercase()
        val into = after.filter { (it.type == TxType.INTERNAL || it.type == TxType.PAYBILL) && b.accountNo.isNotBlank() && it.account.trim() == b.accountNo }.sumOf { it.amount }
        val toMpesa = after.filter { it.type == TxType.INTERNAL && it.raw.contains("received", true) && it.counterparty.uppercase().contains(word) }.sumOf { it.amount }
        val out = after.filter { it.wallet.equals(b.name, true) && (it.type == TxType.BANK_SENT || it.type == TxType.LOAN_REPAY) }.sumOf { it.amount }
        return b.balance + into - toMpesa - out
    }

    fun rebuild(
        s: Snapshot,
        rules: Map<String, String>,
        overrides: Map<String, String>,
        manual: List<ManualLoan>,
        rides: List<Ride> = emptyList(),
        receivables: List<Receivable> = emptyList(),
        gigTags: Map<String, String> = emptyMap(),
        gigSenders: Map<String, String> = emptyMap(),
        pending: List<PendingPay> = emptyList(),
        keywordRules: Map<String, String> = emptyMap(),
    ): Snapshot {
        val cat = Categorizer(rules, overrides, keywordRules)
        val m = matchRides(rides, s.rawTxs)
        val txs = (s.rawTxs + m.extra).map { t ->
            val c = cat.categorize(t)
            val provider = m.paidByMpesa[t.id]
            // a ride match wins over the automatic category, but never over something the user picked
            if (provider != null && t.id !in overrides && t.counterparty.uppercase() !in rules) {
                t.copy(category = Cat.FUEL, account = "$provider ride")
            } else t.copy(category = c)
        }.sortedByDescending { it.time }

        // Money from someone who owes you counts as repayment (not income) until the debt is covered.
        val repaidIds = HashSet<String>()
        val recv = receivables.map { r ->
            var left = r.total
            txs.filter {
                it.type == TxType.RECEIVED && it.time > r.asOf && it.counterparty.contains(r.matchName, ignoreCase = true) &&
                    (it.id !in overrides || overrides[it.id] == Cat.REPAID_TO_ME)
            }.sortedBy { it.time }.forEach { t -> if (left > 0.5) { repaidIds += t.id; left -= t.amount } }
            r to max(0.0, left)
        }
        // Side gigs: pending pay is cleared by money tagged with that gig or coming from the payer.
        val gigOf = HashMap<String, String>()
        val pend = pending.sortedBy { it.due }.map { p ->
            var left = p.amount - p.paidOther
            txs.filter { t ->
                t.type == TxType.RECEIVED && t.id !in repaidIds && t.id !in gigOf && t.time > p.asOf - DAY &&
                    (gigTags[t.id]?.equals(p.gig, true) == true ||
                        (gigTags[t.id] == null && p.payer.isNotBlank() && t.counterparty.contains(p.payer, ignoreCase = true) &&
                            (t.id !in overrides || overrides[t.id] == Cat.SIDE)))
            }.sortedBy { it.time }.forEach { t -> if (left > 0.5) { gigOf[t.id] = p.gig; left -= t.amount } }
            p to max(0.0, left)
        }
        txs.forEach { t ->
            if (t.type != TxType.RECEIVED || t.id in repaidIds || t.id in gigOf) return@forEach
            val g = gigTags[t.id] ?: (if (t.id !in overrides) gigSenders[t.counterparty.uppercase()] else null)
            if (g != null) gigOf[t.id] = g
        }

        val instalmentIds = manual.flatMap { l -> Analytics.instalmentsPaid(l, txs).map { it.id } }.toSet()
        val finalTxs = if (repaidIds.isEmpty() && instalmentIds.isEmpty() && gigOf.isEmpty()) txs else txs.map {
            when (it.id) {
                in repaidIds -> it.copy(category = Cat.REPAID_TO_ME)
                in gigOf -> it.copy(category = Cat.SIDE, account = gigOf.getValue(it.id))
                in instalmentIds -> it.copy(category = Cat.LOANPAY)
                else -> it
            }
        }

        val ml = manual.map { l -> l to remaining(l, finalTxs) }
        return s.copy(txs = finalTxs, manualLoans = ml, receivables = recv, pending = pend)
    }

    data class RideMatch(
        val paidByMpesa: Map<String, String>, // SMS tx id -> provider (the M-Pesa payment was a ride)
        val extra: List<Tx>,                  // rides paid in cash / other card (not in SMS)
    )

    /**
     * Links captured rides to M-Pesa payments:
     *  - a send/till payment to the driver (same first name, or ~same amount around the same time) is the ride;
     *  - an M-Pesa card charge for the same trip already covers it;
     *  - otherwise a receipt with a fare becomes its own "cash/card" ride.
     */
    fun matchRides(rides: List<Ride>, sms: List<Tx>): RideMatch {
        val paid = LinkedHashMap<String, String>()
        val extra = ArrayList<Tx>()
        val payments = sms.filter { it.type == TxType.SENT || it.type == TxType.TILL }
        val cards = sms.filter { it.type == TxType.CARD && !it.counterparty.contains("EATS") }
        // fares first (strongest signal), then driver-name hints
        for (r in rides.sortedBy { if (it.isHint) 1 else 0 }) {
            val pay = payments.filter { it.id !in paid && RideParser.matches(r, it.amount, it.time, it.counterparty) }
                .minByOrNull { abs(it.time - r.time) + (if (r.isHint) 0.0 else abs(it.amount - r.amount) * 60_000).toLong() }
            if (pay != null) { paid[pay.id] = r.provider; continue }
            if (r.isHint) continue
            val byCard = cards.any {
                it.counterparty.contains(r.provider.uppercase()) && abs(it.amount - r.amount) <= r.amount * 0.05 + 1 && abs(it.time - r.time) < 12 * 3_600_000L
            }
            if (!byCard) extra += Tx(r.id, r.time, TxType.RIDE, r.amount, "${r.provider.uppercase()} RIDE", wallet = "Cash/Card", raw = r.text)
        }
        return RideMatch(paid, extra)
    }

    /** Rides that aren't in the SMS at all (kept for tests/compat). */
    fun rideTxs(rides: List<Ride>, sms: List<Tx>): List<Tx> = matchRides(rides, sms).extra

    /**
     * One-off M-Pesa sends that may have been ride payments: a person you've paid only once and who has never
     * sent you money, KES 100–3,000, still in the default "Family & friends" category.
     */
    fun possibleRides(txs: List<Tx>, rules: Map<String, String>, overrides: Map<String, String>, since: Long): List<Tx> {
        val sentCount = txs.filter { it.type == TxType.SENT || it.type == TxType.BANK_SENT }.groupingBy { it.counterparty }.eachCount()
        val received = txs.filter { it.type == TxType.RECEIVED }.map { it.counterparty }.toSet()
        return txs.filter {
            it.type == TxType.SENT && it.time >= since && it.amount in 100.0..3000.0 &&
                it.category == Cat.FAMILY && it.id !in overrides && it.counterparty.uppercase() !in rules &&
                sentCount[it.counterparty] == 1 && it.counterparty !in received
        }
    }

    /**
     * Recent money in from people that hasn't been explained yet — candidates for "was this a side gig?".
     * Skips salary/known senders, loans, debts repaid to you and anything the user already answered.
     */
    fun untaggedIncome(s: Snapshot, overrides: Map<String, String>, rules: Map<String, String>, since: Long): List<Tx> =
        s.txs.filter {
            it.type == TxType.RECEIVED && it.time >= since && it.amount >= 200 && it.category == Cat.RECEIVED &&
                it.id !in overrides && it.counterparty.uppercase() !in rules
        }

    /** Balance minus the principal paid since [ManualLoan.asOf]. */
    fun remaining(l: ManualLoan, txs: List<Tx>): Double {
        val paid = Analytics.instalmentsPaid(l, txs)
        val principal = if (l.principalPart > 0) paid.size * l.principalPart else paid.sumOf { it.amount }
        return max(0.0, l.balance - principal)
    }

    /** Savings in the loan account, growing by [ManualLoan.savingsPart] per instalment. */
    fun loanAccountSavings(l: ManualLoan, txs: List<Tx>): Double =
        l.savingsBalance + Analytics.instalmentsPaid(l, txs).size * l.savingsPart

    /** Savings in the second account, growing by [ManualLoan.otherPart] per instalment. */
    fun otherSavings(l: ManualLoan, txs: List<Tx>): Double =
        l.otherSavingsBalance + Analytics.instalmentsPaid(l, txs).size * l.otherPart

    fun lenderSavings(l: ManualLoan, txs: List<Tx>): Double = loanAccountSavings(l, txs) + otherSavings(l, txs)
}
