package com.myfinance.app.data

import kotlin.math.abs

/** An Uber/Bolt trip captured from a notification (app or receipt email). */
data class Ride(
    val id: String,
    val time: Long,
    val provider: String, // "Uber" or "Bolt"
    val amount: Double,
    val source: String,   // package the notification came from
    val text: String,     // short snippet, for checking
    val driver: String = "", // driver's first name, if the notification mentions it
) {
    /** A "driver is arriving" hint with no fare yet. */
    val isHint get() = amount <= 0.0
}

object RideParser {
    val UBER_PKGS = setOf("com.ubercab")
    val BOLT_PKGS = setOf("ee.mtakso.client")
    val EMAIL_PKGS = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider")
    val WATCHED = UBER_PKGS + BOLT_PKGS + EMAIL_PKGS

    private val CUR = "(?:KES|KSh|Ksh|KSH)\\.?\\s?"
    private val AMT = "([\\d,]+(?:\\.\\d{1,2})?)"
    private val TOTAL = Regex("(?:total|paid|pay|charged|fare|amount)[^\\d\\n]{0,30}?$CUR$AMT", RegexOption.IGNORE_CASE)
    private val ANY = Regex("$CUR$AMT", RegexOption.IGNORE_CASE)
    private val TRIP = Regex("\\b(trip|ride|journey|receipt)\\b", RegexOption.IGNORE_CASE)
    private val FOOD = Regex("\\b(eats|order|delivery|restaurant|meal|food|courier|package)\\b", RegexOption.IGNORE_CASE)
    private val PROMO = Regex("%|\\boff\\b|promo|discount|\\bsave\\b|\\boffer|\\bdeal|voucher|\\bcode\\b|T&Cs", RegexOption.IGNORE_CASE)

    sealed class Result {
        data class Found(val ride: Ride) : Result()
        data class Ignored(val reason: String) : Result()
    }

    private val NOT_NAMES = setOf(
        "YOUR", "THE", "DRIVER", "TRIP", "RIDE", "UBER", "BOLT", "CAPTAIN", "WE", "YOU", "THIS", "WILL", "HAS", "HAD",
        "ARRIVED", "ARRIVING", "CANCELLED", "ACCEPTED", "RATING", "FOR", "AND", "NOT", "CAN", "COULD", "SHOULD", "SOON",
        "NOW", "HERE", "THERE", "ALMOST", "STILL", "JUST",
    )
    private val DRIVER_PATTERNS = listOf(
        Regex("(?:^|\\n)\\s*([A-Z][\\p{L}'-]+)(?: [A-Z]\\.?)? (?:is|has) (?:arriving|arrived|here|nearby|waiting|on (?:the|their|his|her) way|outside|almost there)", RegexOption.IGNORE_CASE),
        Regex("(?:rode|riding|trip) with ([A-Z][\\p{L}'-]+)", RegexOption.IGNORE_CASE),
        Regex("(?:your driver|driver|meet)[,:]?\\s+([A-Z][\\p{L}'-]+)", RegexOption.IGNORE_CASE),
    )

    /** Driver's first name from texts like "Joshua is arriving in 2 min" or "You rode with Joshua". */
    fun driverName(text: String): String {
        for (p in DRIVER_PATTERNS) {
            val n = p.find(text)?.groupValues?.get(1)?.uppercase() ?: continue
            if (n.length >= 3 && n !in NOT_NAMES) return n
        }
        return ""
    }

    fun parse(pkg: String, text: String, time: Long, key: String = ""): Result {
        val r = parseFare(pkg, text, time)
        if (r is Result.Found) return Result.Found(r.ride.copy(driver = driverName(text)))
        // No fare, but the Uber/Bolt app named the driver: keep as a hint to match the M-Pesa payment.
        if (pkg in UBER_PKGS || pkg in BOLT_PKGS) {
            val d = driverName(text)
            if (d.isNotEmpty()) {
                val provider = if (pkg in UBER_PKGS) "Uber" else "Bolt"
                return Result.Found(Ride("HINT-$provider-$d-${time / 600_000}", time, provider, 0.0, pkg, text.take(160), d))
            }
        }
        return r
    }

    private fun parseFare(pkg: String, text: String, time: Long): Result {
        val provider = when {
            pkg in UBER_PKGS -> "Uber"
            pkg in BOLT_PKGS -> "Bolt"
            Regex("\\buber\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Uber"
            Regex("\\bbolt\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Bolt"
            else -> return Result.Ignored("not Uber/Bolt")
        }
        if (FOOD.containsMatchIn(text)) return Result.Ignored("food/delivery")
        if (PROMO.containsMatchIn(text)) return Result.Ignored("promotion")
        if (!TRIP.containsMatchIn(text)) return Result.Ignored("no trip/receipt wording")
        val m = TOTAL.find(text) ?: ANY.findAll(text).toList().takeIf { it.size == 1 }?.first()
            ?: return Result.Ignored("no single amount found")
        val amount = amt(m.groupValues[1])
        if (amount < 50 || amount > 20_000) return Result.Ignored("amount $amount out of range")
        val id = "RIDE-$provider-${(time / 60_000)}-${amount.toLong()}"
        return Result.Found(Ride(id, time, provider, amount, pkg, text.take(160)))
    }

    /** True if [r] is the same trip as one already in [rides] (app notification + email for one ride). */
    fun isDuplicate(r: Ride, rides: List<Ride>): Boolean = rides.any {
        if (r.isHint) it.isHint && it.driver == r.driver && abs(it.time - r.time) < 2 * 3_600_000L
        else !it.isHint && it.provider == r.provider && abs(it.amount - r.amount) < 1.0 && abs(it.time - r.time) < 6 * 3_600_000L
    }

    private const val WINDOW = 3 * 3_600_000L

    /** Does an M-Pesa send/till payment [amount] at [time] to [counterparty] look like payment for [r]? */
    fun matches(r: Ride, amount: Double, time: Long, counterparty: String): Boolean {
        if (abs(time - r.time) > WINDOW) return false
        val nameMatch = r.driver.isNotEmpty() && counterparty.uppercase().split(" ").firstOrNull() == r.driver
        // riders often round up the fare (437 -> 450), so allow a small difference
        val amountMatch = !r.isHint && abs(amount - r.amount) <= maxOf(50.0, r.amount * 0.10)
        return nameMatch || amountMatch
    }
}
