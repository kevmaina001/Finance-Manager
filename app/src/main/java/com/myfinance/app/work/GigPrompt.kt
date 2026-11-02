package com.myfinance.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myfinance.app.MainActivity
import com.myfinance.app.data.Cat
import com.myfinance.app.data.Categorizer
import com.myfinance.app.data.Parser
import com.myfinance.app.data.Store
import com.myfinance.app.logic.ksh
import com.myfinance.app.model.Sms
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType

/**
 * When M-Pesa money from a person arrives, asks right away: "Was this a side gig?"
 * Money from a pending-pay payer is recognised automatically.
 */
class GigSmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val addr = msgs.firstOrNull()?.originatingAddress?.trim() ?: return
        if (addr.startsWith("+") || addr.firstOrNull()?.isDigit() == true) return // only business senders
        val body = msgs.joinToString("") { it.messageBody ?: "" }.replace(Regex("\\s+"), " ").trim()
        val time = msgs.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val pending = goAsync()
        Thread {
            try { GigPrompt.check(ctx, Sms(time, addr, body)) } finally { pending.finish() }
        }.start()
    }
}

/** Handles the notification buttons: tag as a gig / not a gig. */
class GigActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(GigPrompt.EXTRA_TX) ?: return
        val store = Store(ctx)
        when (intent.action) {
            GigPrompt.ACTION_TAG -> store.tagGig(id, intent.getStringExtra(GigPrompt.EXTRA_NAME) ?: "", intent.getStringExtra(GigPrompt.EXTRA_GIG) ?: return, false)
            GigPrompt.ACTION_NOT -> store.untagGig(id)
        }
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }
}

object GigPrompt {
    const val ACTION_TAG = "com.myfinance.app.GIG_TAG"
    const val ACTION_NOT = "com.myfinance.app.GIG_NOT"
    const val EXTRA_TX = "tx"
    const val EXTRA_GIG = "gig"
    const val EXTRA_NAME = "name"
    const val EXTRA_AMOUNT = "amount"
    private const val CHANNEL = "gigs"

    fun check(ctx: Context, sms: Sms) {
        val store = Store(ctx)
        val txs = Parser(store.ownerName).parse(listOf(sms)).txs.filter { it.type == TxType.RECEIVED }
        if (txs.isEmpty()) return
        val cat = Categorizer(store.userRules, store.txOverrides, store.keywordRules)
        for (t in txs) {
            if (!store.firstAsk(t.id)) continue
            val known = store.gigTags[t.id] ?: store.gigSenders[t.counterparty.uppercase()]
            val pay = store.pendingPays.firstOrNull { it.payer.isNotBlank() && t.counterparty.contains(it.payer, true) && t.time > it.asOf - 86_400_000L }
            val owesMe = store.receivables.any { it.matchName.isNotBlank() && t.counterparty.contains(it.matchName, true) }
            when {
                pay != null -> notify(ctx, t, "💼 Paid for ${pay.gig}: ${ksh(t.amount)}", "From ${t.counterparty}. Marked as side-gig income.", emptyList())
                known != null -> notify(ctx, t, "💼 ${known}: +${ksh(t.amount)}", "From ${t.counterparty} — counted as side-gig income.", emptyList())
                owesMe -> Unit
                cat.categorize(t) == Cat.RECEIVED && t.amount >= 200 -> ask(ctx, store, t)
            }
        }
    }

    private fun ask(ctx: Context, store: Store, t: Tx) {
        val actions = ArrayList<NotificationCompat.Action>()
        store.gigNames.firstOrNull()?.let { last ->
            actions += NotificationCompat.Action(0, "💼 $last", action(ctx, t, ACTION_TAG, last))
        }
        actions += NotificationCompat.Action(0, if (actions.isEmpty()) "💼 Side gig…" else "Other gig…", open(ctx, t))
        actions += NotificationCompat.Action(0, "Not a gig", action(ctx, t, ACTION_NOT, null))
        notify(ctx, t, "+${ksh(t.amount)} from ${t.counterparty}", "Was this side-gig money? Tag it so your side income is tracked.", actions)
    }

    private fun action(ctx: Context, t: Tx, act: String, gig: String?): PendingIntent {
        val i = Intent(ctx, GigActionReceiver::class.java).setAction(act)
            .putExtra(EXTRA_TX, t.id).putExtra(EXTRA_NAME, t.counterparty).putExtra(EXTRA_GIG, gig)
        return PendingIntent.getBroadcast(ctx, (t.id + act).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Opens the app straight into the "which gig?" dialog for this payment. */
    fun open(ctx: Context, t: Tx): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TX, t.id).putExtra(EXTRA_NAME, t.counterparty).putExtra(EXTRA_AMOUNT, t.amount)
        return PendingIntent.getActivity(ctx, t.id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(ctx: Context, t: Tx, title: String, text: String, actions: List<NotificationCompat.Action>) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Side gig money", NotificationManager.IMPORTANCE_HIGH))
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx, t))
            .setAutoCancel(true)
        actions.forEach { b.addAction(it) }
        NotificationManagerCompat.from(ctx).notify(t.id.hashCode(), b.build())
    }
}
