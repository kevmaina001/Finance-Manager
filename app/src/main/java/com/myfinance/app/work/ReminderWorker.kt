package com.myfinance.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.myfinance.app.MainActivity
import com.myfinance.app.data.Repo
import com.myfinance.app.data.Store
import com.myfinance.app.logic.Advisor
import com.myfinance.app.logic.Analytics
import com.myfinance.app.logic.fmt
import com.myfinance.app.logic.ksh
import java.util.concurrent.TimeUnit

/** Runs a few times a day: loan due-date reminders and weekly-allowance warnings. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val store = Store(ctx)
        val snap = Repo.load(ctx, store, days = 60)
        val now = System.currentTimeMillis()

        Advisor.dueItems(snap, now).forEach { d ->
            val due = d.due ?: return@forEach
            val n = Analytics.daysUntil(due, now)
            if (n <= 3 && store.firstTimeToday("loan-${d.lender}")) {
                val title = when {
                    n < 0 -> "${d.lender} is overdue"
                    n == 0 -> "${d.lender} is due today"
                    else -> "${d.lender} due in $n day${if (n == 1) "" else "s"}"
                }
                notify(ctx, d.lender.hashCode(), title, "${ksh(d.amount)} due ${fmt(due)}. Pay from salary/savings — not a new loan.")
            }
        }

        // Side-gig pay that's due or late: a nudge to follow up
        snap.pending.filter { it.second > 0 }.forEach { (p, left) ->
            val n = Analytics.daysUntil(p.due, now)
            if (n <= 0 && store.firstTimeToday("gig-${p.id}")) {
                notify(
                    ctx, p.id.hashCode(), if (n == 0) "Side-gig pay due today: ${p.gig}" else "Side-gig pay ${-n}d late: ${p.gig}",
                    "${ksh(left)} still pending${if (p.payer.isNotBlank()) " from ${p.payer}" else ""}. Worth a follow-up message.",
                )
            }
        }

        val spent = Analytics.weekSpent(snap.txs, now)
        val allowance = store.weeklyAllowance
        if (spent > allowance && store.firstTimeToday("weekly-over")) {
            notify(ctx, 1001, "Weekly allowance overspent", "${ksh(spent)} spent of ${ksh(allowance)} this week.")
        } else if (spent > allowance * 0.8 && spent <= allowance && store.firstTimeToday("weekly-80")) {
            notify(ctx, 1002, "80% of weekly allowance used", "${ksh(allowance - spent)} left until Monday.")
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "reminders"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(8, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun notify(ctx: Context, id: Int, title: String, text: String) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(id, n)
        }
    }
}
