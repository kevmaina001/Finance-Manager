@file:OptIn(ExperimentalLayoutApi::class)

package com.myfinance.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.Cat
import com.myfinance.app.data.Snapshot
import com.myfinance.app.logic.Analytics
import com.myfinance.app.logic.fmt
import com.myfinance.app.logic.ksh
import com.myfinance.app.model.PendingPay
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import java.util.UUID

private const val DAY = 86_400_000L

/** Side-gig money received in [from, to), grouped by gig, biggest first. */
fun gigIncome(s: Snapshot, from: Long, to: Long): List<Pair<String, Double>> =
    s.txs.filter { it.type == TxType.RECEIVED && it.category == Cat.SIDE && it.time in from until to }
        .groupBy { it.account.ifBlank { it.counterparty } }
        .map { (g, l) -> g to l.sumOf { it.amount } }
        .sortedByDescending { it.second }

/** "Was this side-gig money?" — pick a gig (or type a new one). */
@Composable
fun GigTagDialog(tx: Tx, names: List<String>, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit, onNot: () -> Unit) {
    var pick by remember { mutableStateOf(names.firstOrNull() ?: "") }
    var typed by remember { mutableStateOf("") }
    var always by remember { mutableStateOf(false) }
    val gig = typed.trim().ifBlank { pick }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Side gig money?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("+${ksh(tx.amount)} from ${tx.counterparty}", fontWeight = FontWeight.SemiBold, color = Green)
                if (names.isNotEmpty()) {
                    Text("Which gig?", style = MaterialTheme.typography.bodySmall, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEach { n -> FilterChip(selected = typed.isBlank() && pick == n, onClick = { pick = n; typed = "" }, label = { Text(n) }) }
                    }
                }
                TextInput(if (names.isEmpty()) "Gig name (e.g. Fibre install, Website)" else "Or a new gig", typed) { typed = it }
                Row(Modifier.clickable { always = !always }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Text("Always count money from ${tx.counterparty} as this gig", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = gig.isNotBlank(), onClick = { onSave(gig, always) }) { Text("Save as side gig") } },
        dismissButton = { TextButton(onClick = onNot) { Text("Not a gig") } },
    )
}

/** Add/edit work that's done but not paid yet. */
@Composable
fun PendingDialog(p: PendingPay, names: List<String>, onDismiss: () -> Unit, onSave: (PendingPay) -> Unit, onDelete: () -> Unit) {
    val isNew = p.gig.isEmpty()
    var gig by remember { mutableStateOf(p.gig) }
    var payer by remember { mutableStateOf(p.payer) }
    var amount by remember { mutableStateOf(p.amount.str()) }
    var days by remember { mutableStateOf(Analytics.daysUntil(p.due).coerceAtLeast(0).toString()) }
    var cash by remember { mutableStateOf(p.paidOther.str()) }
    var note by remember { mutableStateOf(p.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Pending pay" else "Edit pending pay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInput("Gig / job", gig) { gig = it }
                if (isNew && names.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.take(6).forEach { n -> FilterChip(selected = gig == n, onClick = { gig = n }, label = { Text(n) }) }
                }
                NumberField("Amount you're owed", amount, { amount = it })
                NumberField("Expected in how many days", days, { days = it })
                TextInput("Who pays (name on M-Pesa)", payer) { payer = it }
                Text("When money from this name arrives, it's matched automatically. Leave blank to match only money you tag with this gig.", style = MaterialTheme.typography.bodySmall, color = Muted)
                NumberField("Already paid in cash / other way", cash, { cash = it })
                TextInput("Note (optional)", note) { note = it }
            }
        },
        confirmButton = {
            TextButton(enabled = gig.isNotBlank() && amount.num() > 0, onClick = {
                val due = Analytics.startOfDay(System.currentTimeMillis()) + days.num().toLong() * DAY
                onSave(p.copy(gig = gig.trim(), payer = payer.trim().uppercase(), amount = amount.num(), due = due, paidOther = cash.num(), note = note.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

fun newPending() = PendingPay(UUID.randomUUID().toString(), "", "", 0.0, System.currentTimeMillis() + 7 * DAY, System.currentTimeMillis())

/** Home card: recent money in that isn't explained yet. */
@Composable
fun UntaggedIncomeCard(list: List<Tx>, onGig: (Tx) -> Unit, onNot: (Tx) -> Unit) {
    var all by remember { mutableStateOf(false) }
    SectionCard("Money in — side gig?") {
        Text("Tag side-gig pay so you can see what your gigs really earn.", style = MaterialTheme.typography.bodySmall, color = Muted)
        (if (all) list else list.take(3)).forEach { tx ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tx.counterparty, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("+${ksh(tx.amount)} · ${fmt(tx.time, "EEE d MMM, HH:mm")}", style = MaterialTheme.typography.bodySmall, color = Green)
                }
                TextButton(onClick = { onGig(tx) }) { Text("💼 Gig") }
                TextButton(onClick = { onNot(tx) }) { Text("No", color = Muted) }
            }
        }
        if (list.size > 3) TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show all ${list.size}") }
    }
}

/** Business tab: side-gig earnings and pending pay. */
fun LazyListScope.sideGigItems(s: Snapshot, onEdit: (PendingPay) -> Unit) {
    val now = System.currentTimeMillis()
    val (a, b) = Analytics.monthRange(0, now)
    val (pa, pb) = Analytics.monthRange(-1, now)
    val thisMonth = gigIncome(s, a, b)
    val lastMonth = gigIncome(s, pa, pb).sumOf { it.second }
    val waiting = s.pending.sumOf { it.second }
    item {
        HeroCard("Side gigs · ${fmt(a, "MMMM")}", thisMonth.sumOf { it.second }, sub = "Last month ${ksh(lastMonth)}") {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill("Pending pay", ksh(waiting))
                thisMonth.take(3).forEach { (g, v) -> HeroPill(g, ksh(v)) }
            }
        }
    }
    item {
        SectionHeader("Waiting to be paid", action = {
            TextButton(onClick = { onEdit(newPending()) }) { Icon(Icons.Filled.Add, null); Text("Add") }
        })
    }
    val open = s.pending.filter { it.second > 0 }.sortedBy { it.first.due }
    val done = s.pending.filter { it.second <= 0 }
    if (open.isEmpty()) item {
        Text(
            "Did some work and waiting for the money? Add it here — it'll count as money coming in, and be ticked off when the M-Pesa lands.",
            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.fillMaxWidth(),
        )
    }
    items(open, key = { it.first.id }) { (p, left) -> PendingCard(p, left, now) { onEdit(p) } }
    if (done.isNotEmpty()) {
        item { SectionHeader("Paid") }
        items(done, key = { it.first.id }) { (p, left) -> PendingCard(p, left, now) { onEdit(p) } }
    }
}

@Composable
private fun PendingCard(p: PendingPay, left: Double, now: Long, onClick: () -> Unit) {
    val got = p.amount - left
    val n = Analytics.daysUntil(p.due, now)
    SectionCard(modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.gig, fontWeight = FontWeight.Bold)
                if (p.payer.isNotBlank() || p.note.isNotBlank())
                    Text(listOf(p.payer, p.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(ksh(p.amount), fontWeight = FontWeight.Bold)
        }
        if (left <= 0) Text("✓ Paid in full", color = Green, fontWeight = FontWeight.Medium)
        else {
            LinearProgressIndicator(
                progress = { (got / p.amount).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Green,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {},
            )
            Row {
                Text(
                    when { n < 0 -> "Late — expected ${fmt(p.due)}"; n == 0 -> "Expected today"; n == 1 -> "Expected tomorrow"; else -> "Expected ${fmt(p.due, "EEE d MMM")}" },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (n < 0) Red else if (n <= 1) Amber else Muted,
                )
                Text(if (got > 0) "${ksh(got)} in · ${ksh(left)} left" else "${ksh(left)} pending", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}
