package com.myfinance.app.work

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myfinance.app.data.RideParser
import com.myfinance.app.data.Store
import com.myfinance.app.logic.fmt

/**
 * Listens only for Uber, Bolt and email-app notifications and keeps Uber/Bolt trip receipts.
 * Everything else is dropped immediately and never stored.
 */
class RideCaptureService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in RideParser.WATCHED) return
        val e = n.notification?.extras ?: return
        val parts = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ) + (e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList() ?: emptyList())
        val text = parts.joinToString("\n").trim()
        if (text.isEmpty()) return
        // For email apps, only look at messages that mention Uber or Bolt at all.
        if (n.packageName in RideParser.EMAIL_PKGS && !Regex("\\b(uber|bolt)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return

        val store = Store(applicationContext)
        val time = n.postTime
        val oneLine = text.replace(Regex("\\s+"), " ").take(90)
        when (val r = RideParser.parse(n.packageName, text, time, n.key)) {
            is RideParser.Result.Found -> {
                val rides = store.rides
                val ride = r.ride
                val what = if (ride.isHint) "${ride.provider} driver ${ride.driver}" else "${ride.provider} KES ${ride.amount.toLong()}" +
                    if (ride.driver.isNotEmpty()) " (driver ${ride.driver})" else ""
                if (RideParser.isDuplicate(ride, rides)) {
                    store.logRide("${fmt(time, "d MMM HH:mm")} already have: $what")
                } else {
                    store.rides = rides + ride
                    store.logRide("${fmt(time, "d MMM HH:mm")} ✓ $what")
                }
            }
            is RideParser.Result.Ignored -> store.logRide("${fmt(time, "d MMM HH:mm")} ignored (${r.reason}): $oneLine")
        }
    }
}
