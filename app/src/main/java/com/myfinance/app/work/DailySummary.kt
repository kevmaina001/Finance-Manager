package com.myfinance.app.work

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.myfinance.app.data.Cat
import com.myfinance.app.data.Repo
import com.myfinance.app.data.Snapshot
import com.myfinance.app.data.Store
import com.myfinance.app.logic.Advisor
import com.myfinance.app.logic.Analytics
import com.myfinance.app.logic.DAY_MS
import com.myfinance.app.logic.ksh
import com.myfinance.app.model.TxType
import java.util.Calendar

object DailySummary {
    const val HOUR = 23
    private const val ACTION = "com.myfinance.app.DAILY_SUMMARY"
    private const val NOTIF_ID = 2300

    /** Builds the notification title and text for the day containing [now]. */
    fun build(s: Snapshot, weeklyAllowance: Double, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val start = Analytics.startOfDay(now)
        val today = s.txs.filter { it.time in start until start + DAY_MS }
        val inc = today.filter { it.type == TxType.RECEIVED }
        val out = today.filter { it.type in Cat.spendTypes && it.category !in Cat.notSpending }
        val lent = today.filter { it.category == Cat.LENT }.sumOf { it.amount }
        val fees = today.filter { it.type == TxType.FEE }.sumOf { it.amount } + today.sumOf { it.fee }
        val borrowed = today.filter { it.type == TxType.LOAN_IN }.sumOf { it.amount }
        val repaid = today.filter { it.type == TxType.LOAN_REPAY }.sumOf { it.amount }

        val lines = ArrayList<String>()
        if (inc.isEmpty() && out.isEmpty() && borrowed == 0.0 && repaid == 0.0) {
            lines += "No money moved today 👍"
        } else {
            if (inc.isNotEmpty()) {
                lines += "IN ${ksh(inc.sumOf { it.amount })}: " + inc.groupBy { it.category }
                    .map { (c, l) -> "$c ${ksh(l.sumOf { it.amount })}" }.joinToString(", ")
            }
            if (out.isNotEmpty()) {
                lines += "OUT ${ksh(out.sumOf { it.amount })}: " + out.groupBy { it.category }
                    .map { (c, l) -> c to l.sumOf { it.amount } }.sortedByDescending { it.second }
                    .take(4).joinToString(", ") { "${it.first} ${ksh(it.second)}" }
                out.maxByOrNull { it.amount }?.let { lines += "Biggest: ${ksh(it.amount)} to ${it.counterparty}" }
            }
            if (lent > 0) lines += "Lent out: ${ksh(lent)}"
            if (borrowed > 0) lines += "⚠ Borrowed ${ksh(borrowed)} today"
            if (repaid > 0) lines += "Loans repaid: ${ksh(repaid)}"
            if (fees > 0) lines += "Fees & charges: ${ksh(fees)}"
        }

        val spentWeek = Analytics.weekSpent(s.txs, now)
        val left = weeklyAllowance - spentWeek
        val daysLeft = 6 - Analytics.daysUntil(now, Analytics.weekStart(now)).coerceIn(0, 6) // days after today
        lines += when {
            left < 0 -> "Weekly allowance: over by ${ksh(-left)}"
            daysLeft == 0 -> "Weekly allowance: ${ksh(left)} left — resets tomorrow"
            else -> "Weekly allowance: ${ksh(left)} left (${ksh(left / daysLeft)}/day for $daysLeft days)"
        }

        Advisor.dueItems(s, now).filter { it.due != null && Analytics.daysUntil(it.due, now) in -30..1 }.forEach { d ->
            val n = Analytics.daysUntil(d.due!!, now)
            lines += "⏰ ${d.lender} ${ksh(d.amount)} " + when { n < 0 -> "is OVERDUE"; n == 0 -> "was due today"; else -> "is due tomorrow" }
        }

        s.receivables.filter { it.second > 0 && Analytics.daysUntil(it.first.due, now) in -30..1 }.forEach { (r, left) ->
            val n = Analytics.daysUntil(r.due, now)
            lines += "💰 ${r.person} owes you ${ksh(left)} " + when { n < 0 -> "(LATE)"; n == 0 -> "(was due today)"; else -> "(due tomorrow)" }
        }

        val net = inc.sumOf { it.amount } - out.sumOf { it.amount }
        val title = if (inc.isEmpty() && out.isEmpty()) "Today: no spending"
        else "Today: in ${ksh(inc.sumOf { it.amount })} · out ${ksh(out.sumOf { it.amount })}" + if (net < 0) "" else " ✓"
        return title to lines.joinToString("\n")
    }

    fun send(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val store = Store(ctx)
        val snap = Repo.load(ctx, store, days = 60)
        val (title, text) = build(snap, store.weeklyAllowance)
        ReminderWorker.notify(ctx, NOTIF_ID, title, text)
    }

    /** Arms (or disarms) the alarm for the next 11:00 PM. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, DailySummaryReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (!Store(ctx).dailySummary) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_MONTH, 1)
        }
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, c.timeInMillis, 10 * 60_000L, pi)
    }

    fun isSummaryAction(i: Intent?) = i?.action == ACTION
}

/** Fires at 11 PM (sends the summary and re-arms for tomorrow) and after reboot/app update (re-arms). */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val app = ctx.applicationContext
        if (!DailySummary.isSummaryAction(intent)) {
            DailySummary.schedule(app)
            return
        }
        val pending = goAsync()
        Thread {
            try {
                DailySummary.send(app)
            } finally {
                DailySummary.schedule(app)
                pending.finish()
            }
        }.start()
    }
}
