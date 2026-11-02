@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.myfinance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.Cat
import com.myfinance.app.data.Loans
import com.myfinance.app.logic.Advisor
import com.myfinance.app.logic.Analytics
import com.myfinance.app.logic.fmt
import com.myfinance.app.logic.ksh
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.Receivable
import com.myfinance.app.model.StockBatch
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import java.util.UUID

private val listPad = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
private val gap = Arrangement.spacedBy(10.dp)

// ============================================================== HOME
/** Day-to-day money only. Long-term loans, savings and money owed to you live on the Loans tab. */
@Composable
fun HomeScreen(vm: AppVM, onOpen: (Int) -> Unit = {}) {
    val s = vm.snap ?: return
    val now = System.currentTimeMillis()
    val (a, b) = Analytics.monthRange(0)
    val m = remember(s) { Analytics.month(s.txs, a, b) }
    val advice = remember(s, vm.budgets, vm.weekly, vm.stock) { Advisor.advise(s, vm.budgets, vm.weekly, vm.stock) }
    val tips = advice.filter { it.tag.isEmpty() }
    val spentWeek = remember(s) { Analytics.weekSpent(s.txs) }
    val dayStart = Analytics.startOfDay(now)
    val today = remember(s) { s.txs.filter { it.time >= dayStart } }
    val inToday = today.filter { it.type == TxType.RECEIVED }.sumOf { it.amount }
    val outToday = today.filter { it.type in Cat.spendTypes && it.category !in Cat.notSpending }.sumOf { it.amount }
    val due = remember(s) { Advisor.dueItems(s).filter { it.due != null && Analytics.daysUntil(it.due, now) <= 7 } }
    val incoming = s.receivables.filter { it.second > 0 && Analytics.daysUntil(it.first.due, now) <= 7 }
        .map { (r, left) -> Triple(r.person, left, "Owed to you · ${fmt(r.due, "EEE d MMM")}") } +
        s.pending.filter { it.second > 0 && Analytics.daysUntil(it.first.due, now) in -14..7 }
            .map { (p, left) -> Triple(p.gig, left, "Side gig · ${if (p.due < Analytics.startOfDay(now)) "late" else fmt(p.due, "EEE d MMM")}") }
    val untagged = remember(s) { vm.untaggedIncome() }
    val waiting = s.pending.filter { it.second > 0 }
    val recent = remember(s) { s.txs.filter { it.category.isNotEmpty() }.sortedByDescending { it.time }.take(5) }
    val mpesa = s.mpesaBalance?.second ?: 0.0
    val pochi = s.pochiBalance?.second ?: 0.0
    val cash = s.cash
    var showAll by remember { mutableStateOf(false) }
    var editBank by remember { mutableStateOf<Pair<com.myfinance.app.model.BankAccount, Double>?>(null) }

    LazyColumn(contentPadding = listPad, verticalArrangement = gap) {
        item {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val hello = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
            val first = vm.ownerName.trim().substringBefore(" ").lowercase().replaceFirstChar { it.uppercase() }
            Column(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                Text(if (first.isEmpty()) hello else "$hello, $first", style = MaterialTheme.typography.headlineMedium)
                Text(fmt(now, "EEEE, d MMMM"), color = Muted)
            }
        }
        if (vm.ownerName.isBlank()) item {
            SectionCard("Add your name") {
                Text(
                    "Go to Budget → Settings and enter your name as it appears in bank SMS, so money moved between your own accounts isn't counted as spending.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onOpen(4) }) { Text("Open settings") }
            }
        }
        item {
            val updated = listOfNotNull(s.mpesaBalance?.first, s.pochiBalance?.first).maxOrNull()
            HeroCard("Available now", cash, sub = updated?.let { "Updated ${fmt(it, "d MMM, HH:mm")}" }) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroPill("M-Pesa", ksh(mpesa))
                    HeroPill("Pochi · business", ksh(pochi))
                    s.banks.forEach { bk -> HeroPill("${bk.first.name} ✎", ksh(bk.second)) { editBank = bk } }
                }
            }
        }
        item {
            SectionHeader("Today")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Money in", inToday, Green, Modifier.weight(1f), sign = "+")
                StatTile("Spent", outToday, Red, Modifier.weight(1f), sign = "−")
            }
        }
        item {
            SectionHeader(fmt(a, "MMMM"))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Earned", m.income, Green, Modifier.weight(1f))
                StatTile("Spent", m.spending, Red, Modifier.weight(1f), sub = "Fees ${ksh(m.loanFees + m.mpesaCharges)}")
            }
        }
        if (untagged.isNotEmpty()) item {
            UntaggedIncomeCard(untagged, onGig = { vm.tagRequest = it }, onNot = { vm.notGig(it) })
        }
        if (waiting.isNotEmpty()) item {
            SectionCard(modifier = Modifier.clickable { onOpen(3) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("💼 Side-gig pay pending", fontWeight = FontWeight.SemiBold)
                        Text(waiting.joinToString(", ") { it.first.gig }, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(ksh(waiting.sumOf { it.second }), color = Green, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            val left = vm.weekly - spentWeek
            val daysLeft = (7 - Analytics.daysUntil(now, Analytics.weekStart(now)).coerceIn(0, 6)).coerceAtLeast(1)
            SectionCard("This week") {
                ProgressRow("Allowance", spentWeek, vm.weekly)
                Text(
                    if (left >= 0) "${ksh(left)} left · about ${ksh(left / daysLeft)} a day until Monday"
                    else "Over by ${ksh(-left)} — hold off on extras until Monday",
                    style = MaterialTheme.typography.bodySmall, color = if (left >= 0) Muted else Red,
                )
            }
        }
        if (due.isNotEmpty()) item {
            val total = due.sumOf { it.amount }
            val expected = incoming.sumOf { it.second }
            SectionCard("Due this week", action = { Text(ksh(total), color = Red, fontWeight = FontWeight.Bold) }) {
                due.forEach { d ->
                    val n = Analytics.daysUntil(d.due!!, now)
                    DueLine(
                        d.lender.substringBefore(" ("), "−${ksh(d.amount)}",
                        (if (d.manual) "Instalment · " else "") +
                            when { n < 0 -> "Overdue ${-n}d"; n == 0 -> "Today"; n == 1 -> "Tomorrow"; else -> fmt(d.due, "EEE d MMM") },
                        if (n <= 1) Red else if (n <= 3) Amber else Muted,
                    )
                }
                incoming.forEach { (who, left, whenText) -> DueLine(who, "+${ksh(left)}", whenText, Green) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val have = cash + expected
                Text(
                    when {
                        cash >= total -> "You have enough across M-Pesa, Pochi and the bank to cover this."
                        have >= total -> "Covered once the ${ksh(expected)} you're owed comes in."
                        else -> "Short by ${ksh(total - have)} — cut spending instead of borrowing."
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (have >= total) Green else Red, fontWeight = FontWeight.Medium,
                )
            }
        }
        if (tips.isNotEmpty()) item { SectionHeader("What to do") }
        items(if (showAll) tips else tips.take(3)) { AdviceCard(it) }
        if (tips.size > 3) item {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAll) "Show fewer" else "Show ${tips.size - 3} more")
            }
        }
        if (recent.isNotEmpty()) item {
            SectionCard("Recent activity", action = { TextButton(onClick = { onOpen(1) }) { Text("See all") } }) {
                recent.forEach { TxRow(it, divider = it != recent.last()) { onOpen(1) } }
            }
        }
        item {
            OutlinedButton(onClick = { onOpen(2) }, modifier = Modifier.fillMaxWidth()) { Text("Loans, savings & money owed to you  →") }
        }
    }
    editBank?.let { (acct, bal) ->
        AmountDialog("${acct.name} balance (check your bank app)", bal, onDismiss = { editBank = null }) { v -> vm.setBankBalance(acct.id, v); editBank = null }
    }
}

@Composable
private fun DueLine(name: String, amount: String, whenText: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Text(amount, fontWeight = FontWeight.SemiBold, color = if (amount.startsWith("+")) Green else MaterialTheme.colorScheme.onSurface)
    }
}

// ============================================================== SPENDING
@Composable
fun SpendingScreen(vm: AppVM) {
    val s = vm.snap ?: return
    var offset by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Tx?>(null) }
    val (a, b) = Analytics.monthRange(offset)
    val m = remember(s, offset) { Analytics.month(s.txs, a, b) }
    val shown = remember(m, filter) {
        m.txs.filter { it.category.isNotEmpty() && (filter == null || it.category == filter) }
    }
    val max = m.spendByCat.maxOfOrNull { it.second } ?: 1.0
    val maybeRides = remember(s) { vm.possibleRides() }
    var showAllRides by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (maybeRides.isNotEmpty()) item {
            SectionCard("🚕 Were these rides? (${maybeRides.size})") {
                Text(
                    "One-off payments to people who've never sent you money — often Uber/Bolt drivers paid by M-Pesa. Tap 🚕 to count it as transport.",
                    style = MaterialTheme.typography.bodySmall,
                )
                (if (showAllRides) maybeRides else maybeRides.take(5)).forEach { tx ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tx.counterparty, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text("${ksh(tx.amount)} · ${fmt(tx.time, "EEE d MMM, HH:mm")}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.answerRide(tx, true) }) { Text("🚕 Ride") }
                        TextButton(onClick = { vm.answerRide(tx, false) }) { Text("No") }
                    }
                }
                if (maybeRides.size > 5) TextButton(onClick = { showAllRides = !showAllRides }) {
                    Text(if (showAllRides) "Show fewer" else "Show all ${maybeRides.size}")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset--; filter = null }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                Text(fmt(a, "MMMM yyyy"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (offset < 0) { offset++; filter = null } }) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            SectionCard("Money in · ${ksh(m.moneyIn)}") {
                if (m.moneyIn > m.income) Text("Earned ${ksh(m.income)} — the rest is borrowed money or debts repaid to you", style = MaterialTheme.typography.bodySmall)
                m.incomeByCat.forEach { (c, v) ->
                    Row(Modifier.fillMaxWidth().clickable { filter = if (filter == c) null else c }) {
                        Text(c, Modifier.weight(1f), fontWeight = if (filter == c) FontWeight.Bold else FontWeight.Normal)
                        Text(ksh(v), color = Green)
                    }
                }
            }
        }
        item {
            SectionCard("Spending · ${ksh(m.spending)}") {
                Text("Tap a category to see its transactions", style = MaterialTheme.typography.bodySmall)
                m.spendByCat.forEach { (c, v) ->
                    ProgressRow(if (filter == c) "▶ $c" else c, v, max) { filter = if (filter == c) null else c }
                }
            }
        }
        item {
            SectionCard("Loans & fees") {
                MoneyRow("Borrowed", m.borrowed)
                MoneyRow("Repaid", m.repaid)
                MoneyRow("Loan fees & interest", m.loanFees, Amber)
                MoneyRow("M-Pesa transaction costs", m.mpesaCharges, Amber)
                Row(Modifier.fillMaxWidth()) {
                    Text("Times you used Fuliza", Modifier.weight(1f))
                    Text("${m.fulizaDraws}", color = if (m.fulizaDraws > 0) Amber else Green, fontWeight = FontWeight.Medium)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (filter == null) "All transactions" else filter!!, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (filter != null) TextButton(onClick = { filter = null }) { Text("Show all") }
            }
            Text("Tap a transaction to change its category", style = MaterialTheme.typography.bodySmall)
        }
        items(shown, key = { it.id }) { tx -> TxRow(tx) { editing = tx } }
    }
    editing?.let { tx ->
        CategoryDialog(
            tx, onDismiss = { editing = null },
            onSideGig = if (tx.type == TxType.RECEIVED) ({ editing = null; vm.tagRequest = tx }) else null,
        ) { cat, all -> vm.setCategory(tx, cat, all); editing = null }
    }
}

@Composable
fun TxRow(tx: Tx, divider: Boolean = true, onClick: () -> Unit) {
    val income = tx.type == TxType.RECEIVED
    val transfer = tx.category == Cat.TRANSFER
    val tint = if (transfer) Muted else if (income) Green else Brand
    Column(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text(tx.counterparty.trim().take(1).uppercase().ifEmpty { "•" }, color = tint, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(titleCase(tx.counterparty), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (tx.category == Cat.SIDE && tx.account.isNotBlank() && !tx.account.equals(tx.counterparty, true)) "💼 ${tx.account}" else tx.category} · ${fmt(tx.time, "d MMM, HH:mm")}${if (tx.wallet != "M-PESA") " · ${tx.wallet}" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (transfer) "↔ " else if (income) "+" else "−") + ksh(tx.amount),
                    color = if (transfer) Muted else if (income) Green else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                )
                if (tx.fee > 0) Text("fee ${ksh(tx.fee)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun titleCase(t: String) = t.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

// ============================================================== LOANS
@Composable
fun LoansScreen(vm: AppVM) {
    val s = vm.snap ?: return
    var editing by remember { mutableStateOf<ManualLoan?>(null) }
    var editingR by remember { mutableStateOf<Receivable?>(null) }
    val now = System.currentTimeMillis()
    val (a, b) = Analytics.monthRange(0)
    val (pa, pb) = Analytics.monthRange(-1)
    val m = remember(s) { Analytics.month(s.txs, a, b) }
    val prev = remember(s) { Analytics.month(s.txs, pa, pb) }
    val due = remember(s) { Advisor.dueItems(s) }
    val plan = remember(s, vm.stock) { Advisor.advise(s, vm.budgets, vm.weekly, vm.stock).filter { it.tag == "longterm" } }

    LazyColumn(contentPadding = listPad, verticalArrangement = gap) {
        item {
            val shortTerm = s.loans.sumOf { it.outstanding }
            val longTerm = s.manualLoans.sumOf { it.second }
            val week = due.filter { it.due != null && Analytics.daysUntil(it.due, now) <= 7 }.sumOf { it.amount }
            HeroCard("You owe in total", shortTerm + longTerm) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroPill("Short-term", ksh(shortTerm))
                    HeroPill("Long-term", ksh(longTerm))
                    HeroPill("Due in 7 days", ksh(week))
                }
            }
        }
        item {
            val owedToMe = s.receivables.sumOf { it.second }
            val saved = s.manualLoans.sumOf { (l, _) -> com.myfinance.app.data.Repo.lenderSavings(l, s.txs) }
            val people = s.receivables.count { it.second > 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Owed to you", owedToMe, Green, Modifier.weight(1f), sub = "$people ${if (people == 1) "person" else "people"}")
                StatTile("Your savings", saved, Brand, Modifier.weight(1f), sub = "With lenders")
            }
        }
        if (plan.isNotEmpty()) {
            item { SectionHeader("Long-term plan") }
            items(plan) { AdviceCard(it) }
        }
        item {
            SectionCard("Fees & interest") {
                MoneyRow("This month", m.loanFees, Amber, sub = "Last month ${ksh(prev.loanFees)}")
                if (m.cycling.isNotEmpty() || prev.cycling.isNotEmpty())
                    Text("⚠ Re-borrowed right after repaying: ${m.cycling.size} this month, ${prev.cycling.size} last month", color = Red, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { SectionHeader("Short-term loans · from your SMS") }
        if (s.loans.isEmpty()) item { Text("No outstanding loans found in your SMS 🎉") }
        items(s.loans) { l ->
            SectionCard {
                Row {
                    Text(l.lender, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(ksh(l.outstanding), fontWeight = FontWeight.Bold)
                }
                l.due?.let { d ->
                    val n = Analytics.daysUntil(d, now)
                    Text(
                        when { n < 0 -> "OVERDUE since ${fmt(d)}"; n == 0 -> "Due TODAY"; n == 1 -> "Due tomorrow"; else -> "Due ${fmt(d)} · in $n days" },
                        color = if (n <= 2) Red else if (n <= 7) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(costHint(l.lender), style = MaterialTheme.typography.bodySmall)
                Text("Updated ${fmt(l.lastUpdate, "d MMM HH:mm")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Long-term & private loans", Modifier.weight(1f))
                TextButton(onClick = {
                    editing = ManualLoan(UUID.randomUUID().toString(), "", 0.0, now, 0.0, 1, "")
                }) { Icon(Icons.Filled.Add, null); Text("Add") }
            }
            Text("Loans that don't send SMS, e.g. private lenders. Payments to the matching name reduce the balance automatically.", style = MaterialTheme.typography.bodySmall)
        }
        items(s.manualLoans) { (l, rem) ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.lender, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editing = l }) { Icon(Icons.Filled.Edit, "Edit") }
                }
                MoneyRow("Remaining", rem, bold = true)
                MoneyRow("Monthly instalment", l.monthly, sub = "Next due ${fmt(Analytics.nextDue(l, now))}")
                if (l.principalPart > 0) {
                    Text(
                        "Each instalment: ${ksh(l.principalPart)} loan · ${ksh(l.interestPart)} interest · ${ksh(l.savingsPart)} savings" +
                            if (l.otherPart > 0) " · ${ksh(l.otherPart)} to second account" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (l.savingsBalance > 0) MoneyRow("Savings (loan account)", com.myfinance.app.data.Repo.loanAccountSavings(l, s.txs), Green)
                if (l.otherSavingsBalance > 0 || l.otherPart > 0) MoneyRow("Savings (second account)", com.myfinance.app.data.Repo.otherSavings(l, s.txs), Green)
                val per = if (l.principalPart > 0) l.principalPart else l.monthly
                if (per > 0 && rem > 0) Text("≈ ${kotlin.math.ceil(rem / per).toInt()} more payments", style = MaterialTheme.typography.bodySmall)
                Text("Balance ${ksh(l.balance)} set on ${fmt(l.asOf, "d MMM yyyy")} — edit if it's not right", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Owed to you", Modifier.weight(1f))
                TextButton(onClick = {
                    editingR = Receivable(UUID.randomUUID().toString(), "", 0.0, 0.0, now + 7 * 86_400_000L, "", now)
                }) { Icon(Icons.Filled.Add, null); Text("Add") }
            }
            Text("Money you lent out. When they send it back, it's marked \"Debt repaid to me\" — not income.", style = MaterialTheme.typography.bodySmall)
        }
        items(s.receivables) { (r, left) ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.person, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editingR = r }) { Icon(Icons.Filled.Edit, "Edit") }
                }
                if (left <= 0) Text("✓ Fully repaid", color = Green, fontWeight = FontWeight.Medium)
                else {
                    MoneyRow("Still owed", left, Green, bold = true, sub = "${ksh(r.amount)} + ${ksh(r.interest)} interest")
                    val n = Analytics.daysUntil(r.due, now)
                    Text(
                        when { n < 0 -> "LATE — was due ${fmt(r.due)}"; n == 0 -> "Due TODAY"; n == 1 -> "Due tomorrow"; else -> "Due ${fmt(r.due)} · in $n days" },
                        color = if (n < 0) Red else if (n <= 1) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    editing?.let { l -> ManualLoanDialog(l, onDismiss = { editing = null }, onSave = { vm.saveManualLoan(it); editing = null }, onDelete = { vm.deleteManualLoan(l.id); editing = null }) }
    editingR?.let { r -> ReceivableDialog(r, onDismiss = { editingR = null }, onSave = { vm.saveReceivable(it); editingR = null }, onDelete = { vm.deleteReceivable(r.id); editingR = null }) }
}

@Composable
fun ReceivableDialog(r: Receivable, onDismiss: () -> Unit, onSave: (Receivable) -> Unit, onDelete: () -> Unit) {
    var person by remember { mutableStateOf(r.person) }
    var amount by remember { mutableStateOf(r.amount.str()) }
    var interest by remember { mutableStateOf(r.interest.str()) }
    var days by remember { mutableStateOf(Analytics.daysUntil(r.due).coerceAtLeast(0).toString()) }
    var match by remember { mutableStateOf(r.matchName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (r.person.isEmpty()) "Someone owes you" else "Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput("Who", person) { person = it }
                NumberField("Amount they owe", amount, { amount = it })
                NumberField("Extra / interest agreed", interest, { interest = it })
                NumberField("Due in how many days", days, { days = it })
                TextInput("Name in M-Pesa SMS (e.g. JANE)", match) { match = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val due = Analytics.startOfDay(System.currentTimeMillis()) + days.num().toLong() * 86_400_000L
                onSave(r.copy(person = person.ifBlank { "Someone" }, amount = amount.num(), interest = interest.num(), due = due, matchName = match.trim().uppercase().ifBlank { person.trim().uppercase().substringBefore(" ") }))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (r.person.isNotEmpty()) TextButton(onClick = onDelete) { Text("Delete", color = Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun costHint(lender: String) = when (lender) {
    "Fuliza" -> "Cost: 1% per draw + up to KES 30/day while outstanding"
    "KCB M-PESA" -> "Cost: ~8.8% per 30 days (8.79% rollover if not paid in full)"
    "Hustler/Bridge" -> "Cost: cheapest — under 1% a month"
    "M-Shwari" -> "Cost: 7.5% facility fee + excise (~9%). 20% fee refund if repaid in 10 days"
    "Equity" -> "Cost: ~8% per loan"
    "ZKPesa" -> "Cost: ~41% per loan — avoid"
    else -> ""
}

@Composable
fun ManualLoanDialog(l: ManualLoan, onDismiss: () -> Unit, onSave: (ManualLoan) -> Unit, onDelete: () -> Unit) {
    var lender by remember { mutableStateOf(l.lender) }
    var bal by remember { mutableStateOf(l.balance.str()) }
    var monthly by remember { mutableStateOf(l.monthly.str()) }
    var day by remember { mutableStateOf(l.dueDay.toString()) }
    var match by remember { mutableStateOf(l.matchName) }
    var principal by remember { mutableStateOf(l.principalPart.str()) }
    var interest by remember { mutableStateOf(l.interestPart.str()) }
    var savings by remember { mutableStateOf(l.savingsPart.str()) }
    var savingsBal by remember { mutableStateOf(l.savingsBalance.str()) }
    var otherBal by remember { mutableStateOf(l.otherSavingsBalance.str()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (l.lender.isEmpty()) "Add loan" else "Edit loan") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { TextInput("Lender", lender) { lender = it } }
                item { NumberField("Remaining balance today", bal, { bal = it }) }
                item { NumberField("Monthly instalment", monthly, { monthly = it }) }
                item { NumberField("Due day of month (1–28)", day, { day = it }) }
                item { TextInput("Name in M-Pesa SMS (e.g. MARY)", match) { match = it } }
                item { Text("Optional — if the instalment is split:", style = MaterialTheme.typography.bodySmall) }
                item { NumberField("…of which reduces the loan", principal, { principal = it }) }
                item { NumberField("…of which is interest", interest, { interest = it }) }
                item { NumberField("…of which is savings with this lender", savings, { savings = it }) }
                item { NumberField("Savings in the loan account today", savingsBal, { savingsBal = it }) }
                item { NumberField("Savings in a second account today (gets the rest of each instalment)", otherBal, { otherBal = it }) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newBal = bal.num()
                // only reset the "as of" date if the balance was changed by hand
                val asOf = if (newBal != l.balance) System.currentTimeMillis() else l.asOf
                val newSav = savingsBal.num()
                val newOther = otherBal.num()
                val asOf2 = if (newSav != l.savingsBalance || newOther != l.otherSavingsBalance) System.currentTimeMillis() else asOf
                onSave(
                    l.copy(
                        lender = lender.ifBlank { "Loan" }, balance = newBal, asOf = asOf2, monthly = monthly.num(),
                        dueDay = day.num().toInt().coerceIn(1, 28), matchName = match.trim().uppercase(),
                        principalPart = principal.num(), interestPart = interest.num(), savingsPart = savings.num(), savingsBalance = newSav,
                        otherSavingsBalance = newOther,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (l.lender.isNotEmpty()) TextButton(onClick = onDelete) { Text("Delete", color = Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ============================================================== BUSINESS
@Composable
fun BusinessScreen(vm: AppVM) {
    val s = vm.snap ?: return
    var editing by remember { mutableStateOf<StockBatch?>(null) }
    val (a, b) = Analytics.monthRange(0)
    val bizTx = remember(s) { s.txs.filter { it.time in a until b && it.category in Cat.business } }
    val unsold = vm.stock.filter { !it.sold }
    var editingP by remember { mutableStateOf<com.myfinance.app.model.PendingPay?>(null) }

    LazyColumn(contentPadding = listPad, verticalArrangement = gap) {
        sideGigItems(s) { editingP = it }
        item { SectionHeader("Business & stock") }
        item {
            SectionCard("Business money") {
                MoneyRow("Pochi balance", s.pochiBalance?.second ?: 0.0, bold = true)
                MoneyRow("Stock at cost (unsold)", unsold.sumOf { it.cost + it.extraCosts })
                MoneyRow("Expected profit (unsold)", unsold.sumOf { it.expectedProfit }, Green)
                MoneyRow("Profit made (sold)", vm.stock.filter { it.sold }.sumOf { it.realizedProfit }, Green)
                MoneyRow("This month: sales in", bizTx.filter { it.category == Cat.BIZ_SALES }.sumOf { it.amount }, sub = "Stock/costs out ${ksh(bizTx.filter { it.category != Cat.BIZ_SALES }.sumOf { it.amount })}")
            }
        }
        item { DealChecker() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stock", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    editing = StockBatch(UUID.randomUUID().toString(), "", 0.0, "Own money", System.currentTimeMillis(), 0.0, "")
                }) { Icon(Icons.Filled.Add, null); Text("Add stock") }
            }
        }
        items(vm.stock, key = { it.id }) { st ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.name, fontWeight = FontWeight.Bold)
                        Text("Bought ${fmt(st.bought, "d MMM yyyy")} · funded by ${st.fundedBy}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { editing = st }) { Icon(Icons.Filled.Edit, "Edit") }
                }
                MoneyRow("Cost", st.cost + st.extraCosts)
                if (st.sold) {
                    MoneyRow("Sold for", st.soldAmount, sub = fmt(st.soldDate, "d MMM yyyy"))
                    MoneyRow("Profit", st.realizedProfit, if (st.realizedProfit >= 0) Green else Red, bold = true)
                } else {
                    if (st.expectedPrice > 0) MoneyRow("Expected sale", st.expectedPrice, sub = st.expectedSale.ifBlank { null })
                    else Text("Expected sale: ${st.expectedSale.ifBlank { "—" }} (tap edit to add the expected price)", style = MaterialTheme.typography.bodySmall)
                    if (st.expectedPrice > 0) MoneyRow("Expected profit", st.expectedProfit, Green)
                }
            }
        }
        item {
            Text("Business transactions this month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Tag transactions as Business stock / costs / sales in the Spending tab", style = MaterialTheme.typography.bodySmall)
        }
        items(bizTx, key = { "b" + it.id }) { tx -> TxRow(tx) {} }
    }
    editing?.let { st ->
        StockDialog(st, onDismiss = { editing = null }, onSave = { vm.saveStock(it); editing = null }, onDelete = { vm.deleteStock(st.id); editing = null })
    }
    editingP?.let { p ->
        PendingDialog(p, vm.gigNames, onDismiss = { editingP = null }, onSave = { vm.savePending(it); editingP = null }, onDelete = { vm.deletePending(p.id); editingP = null })
    }
}

@Composable
fun DealChecker() {
    var amount by remember { mutableStateOf("30000") }
    var profit by remember { mutableStateOf("9970") }
    var days by remember { mutableStateOf("45") }
    var lender by remember { mutableStateOf("Own money") }
    val r = Advisor.dealCheck(amount.num(), profit.num(), days.num().toInt(), lender)
    SectionCard("Deal checker — is it worth borrowing?") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Amount", amount, { amount = it }, Modifier.weight(1f))
            NumberField("Profit", profit, { profit = it }, Modifier.weight(1f))
            NumberField("Days to sell", days, { days = it }, Modifier.weight(1f))
        }
        Text("Funded by:", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Loans.DEAL_LENDERS.forEach { l -> FilterChip(selected = lender == l, onClick = { lender = l }, label = { Text(l) }) }
        }
        MoneyRow("Loan cost", r.loanCost, Amber)
        MoneyRow("Net profit", r.net, if (r.net > 0) Green else Red, bold = true)
        Text(r.verdict, color = if (r.good) Green else Red, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StockDialog(st: StockBatch, onDismiss: () -> Unit, onSave: (StockBatch) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(st.name) }
    var cost by remember { mutableStateOf(st.cost.str()) }
    var extra by remember { mutableStateOf(st.extraCosts.str()) }
    var funded by remember { mutableStateOf(st.fundedBy) }
    var price by remember { mutableStateOf(st.expectedPrice.str()) }
    var when_ by remember { mutableStateOf(st.expectedSale) }
    var sold by remember { mutableStateOf(st.soldAmount.str()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (st.name.isEmpty()) "Add stock" else "Edit stock") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { TextInput("What is it?", name) { name = it } }
                item { NumberField("Cost", cost, { cost = it }) }
                item { NumberField("Extra costs (transport, ads…)", extra, { extra = it }) }
                item { TextInput("Funded by (Own money / loan name)", funded) { funded = it } }
                item { NumberField("Expected selling price", price, { price = it }) }
                item { TextInput("When you expect to sell (e.g. Dec 2026)", when_) { when_ = it } }
                item { NumberField("Sold for (fill in once sold)", sold, { sold = it }) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val soldAmt = sold.num()
                onSave(
                    st.copy(
                        name = name.ifBlank { "Stock" }, cost = cost.num(), extraCosts = extra.num(), fundedBy = funded,
                        expectedPrice = price.num(), expectedSale = when_, soldAmount = soldAmt,
                        soldDate = if (soldAmt > 0 && st.soldDate == 0L) System.currentTimeMillis() else if (soldAmt > 0) st.soldDate else 0L,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (st.name.isNotEmpty()) TextButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null, tint = Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ============================================================== BUDGET
@Composable
fun BudgetScreen(vm: AppVM) {
    val s = vm.snap ?: return
    var editCat by remember { mutableStateOf<String?>(null) }
    var editWeekly by remember { mutableStateOf(false) }
    var editOwner by remember { mutableStateOf(false) }
    val (a, b) = Analytics.monthRange(0)
    val m = remember(s) { Analytics.month(s.txs, a, b) }
    val spent = m.spendByCat.toMap()
    val week = remember(s) {
        val ws = Analytics.weekStart()
        s.txs.filter { it.time >= ws && it.type in Cat.spendTypes && it.category in Cat.weekly }
    }
    val weekSpent = week.sumOf { it.amount }
    val daysLeft = 7 - Analytics.daysUntil(System.currentTimeMillis(), Analytics.weekStart()).coerceIn(0, 6)

    LazyColumn(contentPadding = listPad, verticalArrangement = gap) {
        item {
            SectionCard("Weekly allowance") {
                ProgressRow("Spent since Monday", weekSpent, vm.weekly) { editWeekly = true }
                val left = vm.weekly - weekSpent
                Text(
                    if (left >= 0) "${ksh(left)} left · about ${ksh(left / daysLeft)} a day for $daysLeft day${if (daysLeft == 1) "" else "s"}"
                    else "Over by ${ksh(-left)} — pause non-essentials until Monday",
                    color = if (left >= 0) Green else Red,
                )
                Text("Covers: ${Cat.weekly.joinToString()}. Send yourself this amount from Equity every Monday.", style = MaterialTheme.typography.bodySmall)
                week.groupBy { it.category }.forEach { (c, l) -> MoneyRow("  $c", l.sumOf { it.amount }) }
                OutlinedButton(onClick = { editWeekly = true }) { Text("Change allowance (${ksh(vm.weekly)})") }
            }
        }
        item {
            SectionCard("Monthly budget · ${fmt(a, "MMMM")}") {
                Text("Tap a category to change its limit", style = MaterialTheme.typography.bodySmall)
                val cats = (vm.budgets.keys + spent.keys.filter { it in Cat.expense }).distinct()
                cats.forEach { c -> ProgressRow(c, spent[c] ?: 0.0, vm.budgets[c] ?: 0.0) { editCat = c } }
                HorizontalDivider()
                MoneyRow("Total budget", vm.budgets.values.sum(), bold = true)
                MoneyRow("Spent so far", m.spending, bold = true)
            }
        }
        item {
            SectionCard("Settings") {
                Text("Your name as it appears in bank SMS (used to spot transfers between your own accounts):", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.ownerName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    IconButton(onClick = { editOwner = true }) { Icon(Icons.Filled.Edit, "Edit") }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily summary at 11:00 PM", fontWeight = FontWeight.Medium)
                        Text("Today's money in/out, top spending, weekly allowance left and loans due tomorrow", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = vm.dailySummary, onCheckedChange = { vm.toggleDailySummary(it) })
                }
                OutlinedButton(onClick = { vm.previewDailySummary() }) { Text("Preview today's summary now") }
                HorizontalDivider()
                val act = LocalContext.current as androidx.fragment.app.FragmentActivity
                val canLock = remember { com.myfinance.app.ui.AppLock.available(act) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔒 Fingerprint lock", fontWeight = FontWeight.Medium)
                        Text(
                            if (canLock) "Ask for your fingerprint (or phone PIN) every time the app opens, and after 30 seconds away"
                            else "Set up a fingerprint or screen lock in your phone's Settings first",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = vm.lockEnabled && canLock, enabled = canLock,
                        // turning it off needs the fingerprint too, so someone holding your phone can't just switch it off
                        onCheckedChange = { on ->
                            if (on) vm.setLock(true)
                            else com.myfinance.app.ui.AppLock.prompt(act, "Turn off fingerprint lock") { ok -> if (ok) vm.setLock(false) }
                        },
                    )
                }
            }
        }
        item { BackupCard(vm) }
        item { RidesCard(vm,m.txs.filter { it.type == TxType.RIDE || it.account.endsWith(" ride") }) }
        item { Spacer(Modifier.width(1.dp)) }
    }

    editCat?.let { c ->
        AmountDialog("Budget for $c", vm.budgets[c] ?: 0.0, onDismiss = { editCat = null }) { vm.setBudget(c, it); editCat = null }
    }
    if (editWeekly) AmountDialog("Weekly allowance", vm.weekly, onDismiss = { editWeekly = false }) { vm.changeWeekly(it); editWeekly = false }
    if (editOwner) {
        var v by remember { mutableStateOf(vm.ownerName) }
        AlertDialog(
            onDismissRequest = { editOwner = false },
            title = { Text("Your full name") },
            text = { TextInput("Name", v) { v = it } },
            confirmButton = { TextButton(onClick = { vm.setOwner(v.trim().uppercase()); editOwner = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editOwner = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun RidesCard(vm: AppVM, rides: List<Tx>) {
    val ctx = LocalContext.current
    val access = remember(vm.snap) { vm.rideAccess() }
    val log = remember(vm.snap) { vm.rideLog() }
    SectionCard("Uber & Bolt rides") {
        Text(
            "Rides paid in cash or by card don't send SMS, so the app picks up the trip receipt from the Uber/Bolt app or your receipt email (Gmail/Outlook). It only looks at those notifications.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (access) "✓ Receipt capture is ON" else "Receipt capture is OFF", Modifier.weight(1f), color = if (access) Green else Red, fontWeight = FontWeight.Medium)
            if (!access) TextButton(onClick = {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Turn on") }
        }
        MoneyRow("Rides this month (${rides.size})", rides.sumOf { it.amount })
        if (log.isNotEmpty()) {
            Text("Recently seen:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            log.take(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

@Composable
fun AmountDialog(title: String, initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var v by remember { mutableStateOf(initial.str()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField("Amount (KES)", v, { v = it }) },
        confirmButton = { Button(onClick = { onSave(v.num()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Save everything to a file the user keeps (phone, Drive, WhatsApp to self) and load it back on a new install. */
@Composable
fun BackupCard(vm: AppVM) {
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.backupTo(uri) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) confirmRestore = uri }

    SectionCard("💾 Backup & restore") {
        Text(
            "Saves your loans, money owed to you, side gigs, bank balances, rules and settings to one file. " +
                "Keep it somewhere safe (Google Drive, or WhatsApp it to yourself) so a new phone or reinstall loses nothing. " +
                "Your SMS aren't in it — the app reads those again from the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (vm.lastBackup > 0) "Last backup: ${fmt(vm.lastBackup, "d MMM yyyy, HH:mm")}" else "No backup yet",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
            color = if (vm.lastBackup > 0 && System.currentTimeMillis() - vm.lastBackup < 30L * 86_400_000L) Green else Amber,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save.launch("my-finance-backup-${fmt(System.currentTimeMillis(), "yyyy-MM-dd")}.json") }) { Text("Back up now") }
            OutlinedButton(onClick = { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Restore") }
        }
        Text(
            "⚠ The file isn't password-protected — store it where only you can see it.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        vm.backupMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = if (msg.contains("✓")) Green else Red)
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything currently in the app (loans, debts, gigs, rules, balances, settings) will be replaced by what's in the backup.") },
            confirmButton = { TextButton(onClick = { vm.restoreFrom(uri); confirmRestore = null }) { Text("Restore", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}
