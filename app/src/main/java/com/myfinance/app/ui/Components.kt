@file:OptIn(ExperimentalMaterial3Api::class)

package com.myfinance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.myfinance.app.data.Cat
import com.myfinance.app.logic.ksh
import com.myfinance.app.model.Advice
import com.myfinance.app.model.Tx

val Green = Color(0xFF1E8E5A)
val Red = Color(0xFFD64545)
val Amber = Color(0xFFE08A1E)

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || action != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                action?.invoke()
            }
            content()
        }
    }
}

/** Small grey caps label above a group of cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Muted)
        action?.invoke()
    }
}

/** True when the user has hidden their balances; the green cards blur their amounts. */
val LocalHideBalances = compositionLocalOf { false }
/** Flips [LocalHideBalances]; null where there's nothing to toggle. */
val LocalToggleHide = compositionLocalOf<(() -> Unit)?> { null }

/** Blurs an amount when balances are hidden (Android 12+); older phones get dots instead, see [privateText]. */
@Composable
fun Modifier.privateBlur(): Modifier =
    if (LocalHideBalances.current && Build.VERSION.SDK_INT >= 31) this.blur(14.dp, BlurredEdgeTreatment.Unbounded) else this

@Composable
fun privateText(s: String): String =
    if (LocalHideBalances.current && Build.VERSION.SDK_INT < 31) "KES ••••••" else s

/** The big green card at the top of a screen. */
@Composable
fun HeroCard(label: String, amount: Double, sub: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    val hidden = LocalHideBalances.current
    val toggle = LocalToggleHide.current
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(HeroBrush).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                if (toggle != null) Text(
                    if (hidden) "👁  Show" else "🙈  Hide",
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).clickable { toggle() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(privateText(ksh(amount)), Modifier.privateBlur(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

/** A pill inside the hero card, e.g. "M-Pesa KES 9,000". */
@Composable
fun HeroPill(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).let { if (onClick != null) it.clickable { onClick() } else it }.background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        Text(privateText(value), Modifier.privateBlur(), color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** A small white tile with one number, used in rows of two. */
@Composable
fun StatTile(label: String, value: Double, color: Color, modifier: Modifier = Modifier, sub: String? = null, sign: String = "") {
    Card(
        modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Spacer(Modifier.height(6.dp))
            Text(sign + ksh(value), style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
        }
    }
}

@Composable
fun MoneyRow(label: String, value: Double, color: Color = Color.Unspecified, bold: Boolean = false, sub: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(ksh(value), color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun ProgressRow(label: String, spent: Double, limit: Double, onClick: (() -> Unit)? = null) {
    val frac = if (limit > 0) (spent / limit).toFloat() else if (spent > 0) 1f else 0f
    val color = when { frac > 1f -> Red; frac > 0.8f -> Amber; else -> Green }
    Column(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text("${ksh(spent)} / ${if (limit > 0) ksh(limit) else "—"}", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
fun AdviceCard(a: Advice) {
    val accent = when (a.level) { 3 -> Red; 2 -> Amber; 0 -> Green; else -> Brand }
    val long = a.body.length > 140 || a.body.lines().size > 3
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().let { if (long) it.clickable { open = !open } else it },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(14.dp)) {
                Text(a.title, color = accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(a.body, style = MaterialTheme.typography.bodyMedium, maxLines = if (open || !long) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                if (long) Text(if (open) "Show less" else "Show more", style = MaterialTheme.typography.labelMedium, color = Brand, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

fun Double.str() = if (this == 0.0) "" else if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()
fun String.num() = this.toDoubleOrNull() ?: 0.0

@Composable
fun CategoryDialog(tx: Tx, onDismiss: () -> Unit, onSideGig: (() -> Unit)? = null, onPick: (String, Boolean) -> Unit) {
    val options = if (tx.type == com.myfinance.app.model.TxType.RECEIVED || tx.type == com.myfinance.app.model.TxType.DEPOSIT) Cat.income else Cat.expense
    var pick by remember { mutableStateOf(tx.category) }
    var all by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx.counterparty, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text("${ksh(tx.amount)} · pick a category", style = MaterialTheme.typography.bodySmall)
                if (onSideGig != null) TextButton(onClick = onSideGig) { Text("💼 It was side-gig pay…") }
                if (tx.category != Cat.TRANSFER) TextButton(onClick = { onPick(Cat.TRANSFER, all) }) {
                    Text(if (tx.type == com.myfinance.app.model.TxType.RECEIVED) "↔ From my own account (not income)" else "↔ Moved to my own account (not spending)")
                }
                if (tx.type in Cat.spendTypes && tx.category != Cat.FUEL) {
                    TextButton(onClick = { onPick(Cat.FUEL, false) }) { Text("🚕 It was an Uber/Bolt ride (this payment only)") }
                }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(options) { c ->
                        Row(Modifier.fillMaxWidth().clickable { pick = c }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pick == c, onClick = { pick = c })
                            Text(c)
                        }
                    }
                }
                Row(Modifier.clickable { all = !all }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all, onCheckedChange = { all = it })
                    Text("Apply to everything from ${tx.counterparty}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(pick, all) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
