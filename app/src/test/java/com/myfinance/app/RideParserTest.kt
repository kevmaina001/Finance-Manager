package com.myfinance.app

import com.myfinance.app.data.Repo
import com.myfinance.app.data.Ride
import com.myfinance.app.data.RideParser
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideParserTest {
    private val gmail = "com.google.android.gm"
    private val t = 1_793_563_200_000L

    private fun found(pkg: String, text: String) =
        (RideParser.parse(pkg, text, t) as? RideParser.Result.Found)?.ride

    @Test fun uberReceiptEmail() {
        val r = found(gmail, "Uber Receipts\nYour Sunday evening trip with Uber\nTotal KSh 512.00 November 1, 2026 Thanks for riding, Alex")
        assertEquals("Uber", r?.provider); assertEquals(512.0, r!!.amount, 0.01)
    }

    @Test fun boltReceiptEmail() {
        val r = found(gmail, "Bolt\nYour Bolt ride receipt\nTotal charged KES 1,350 Ride on 1 Nov 2026 from Ruiru to Thika Road Mall")
        assertEquals("Bolt", r?.provider); assertEquals(1350.0, r!!.amount, 0.01)
    }

    @Test fun uberAppCashTrip() {
        val r = found("com.ubercab", "Trip completed\nPlease pay KES 430 in cash to your driver")
        assertEquals(430.0, r!!.amount, 0.01)
    }

    @Test fun ignoresRealUberEatsPromo() {
        val res = RideParser.parse("com.ubercab.eats", "Up to 40% off Meals\nArtcaffe 2x Pasta Combo @ KES 2290, Galito's 2pc Chicken + Chips + Drink @ KES 599. T&Cs apply.", t)
        assertTrue(res is RideParser.Result.Ignored)
    }

    @Test fun ignoresUberEatsReceipt() {
        assertTrue(RideParser.parse(gmail, "Uber Receipts\nYour Uber Eats order\nTotal KSh 1,553.00", t) is RideParser.Result.Ignored)
    }

    @Test fun ignoresRidePromo() {
        assertTrue(RideParser.parse("com.ubercab", "Save on your next ride\n20% off trips up to KES 300 with code RIDE20", t) is RideParser.Result.Ignored)
    }

    @Test fun ignoresUnrelatedEmail() {
        assertTrue(RideParser.parse(gmail, "Equity Bank\nYour statement is ready. Total KES 5,000", t) is RideParser.Result.Ignored)
    }

    @Test fun dedupesAppAndEmail() {
        val a = Ride("a", t, "Uber", 512.0, "com.ubercab", "")
        val b = Ride("b", t + 60_000, "Uber", 512.0, gmail, "")
        assertTrue(RideParser.isDuplicate(b, listOf(a)))
    }

    @Test fun driverNames() {
        assertEquals("JOSHUA", RideParser.driverName("Joshua is arriving in 2 min\nToyota Axio · KDA 123B"))
        assertEquals("JOSHUA", RideParser.driverName("Your Sunday trip\nYou rode with Joshua\nTotal KSh 550.00"))
        assertEquals("", RideParser.driverName("Your driver is arriving"))
        assertEquals("", RideParser.driverName("Your driver will arrive soon"))
    }

    @Test fun driverHintFromUberApp() {
        val r = found("com.ubercab", "Joshua is arriving\nToyota Axio · KDA 123B")
        assertEquals("JOSHUA", r?.driver); assertTrue(r!!.isHint)
    }

    @Test fun mpesaPaymentToDriverBecomesTransport() {
        // receipt 437, paid driver 450 by M-Pesa 10 minutes earlier -> the send is the ride, no extra ride added
        val ride = Ride("r", t, "Uber", 437.0, gmail, "")
        val send = Tx("s", t - 10 * 60_000, TxType.SENT, 450.0, "JOSHUA OKOTH")
        val friend = Tx("f", t - 60 * 60_000, TxType.SENT, 5000.0, "JANE DOE")
        val m = Repo.matchRides(listOf(ride), listOf(send, friend))
        assertEquals(mapOf("s" to "Uber"), m.paidByMpesa)
        assertEquals(0, m.extra.size)
    }

    @Test fun driverNameMatchesWithoutFare() {
        val hint = Ride("h", t, "Bolt", 0.0, "ee.mtakso.client", "", "JOSHUA")
        val send = Tx("s", t + 25 * 60_000, TxType.SENT, 550.0, "JOSHUA OKOTH")
        val other = Tx("o", t + 20 * 60_000, TxType.SENT, 550.0, "PETER KAMAU")
        assertEquals(mapOf("s" to "Bolt"), Repo.matchRides(listOf(hint), listOf(send, other)).paidByMpesa)
    }

    @Test fun noMatchFarApartOrDifferentAmount() {
        val ride = Ride("r", t, "Uber", 437.0, gmail, "")
        val late = Tx("s", t + 5 * 3_600_000, TxType.SENT, 437.0, "JOSHUA OKOTH")
        val big = Tx("b", t, TxType.SENT, 2000.0, "JANE DOE")
        val m = Repo.matchRides(listOf(ride), listOf(late, big))
        assertTrue(m.paidByMpesa.isEmpty())
        assertEquals(1, m.extra.size) // counted as a cash/card ride instead
    }

    @Test fun skipsRidePaidByMpesaCard() {
        val ride = Ride("r", t, "Uber", 512.0, gmail, "")
        val card = Tx("c", t + 30 * 60_000, TxType.CARD, 512.0, "UBER *TRIP HELP.UBER.COM")
        assertEquals(0, Repo.rideTxs(listOf(ride), listOf(card)).size)
        assertEquals(1, Repo.rideTxs(listOf(ride), emptyList()).size)
    }
}
