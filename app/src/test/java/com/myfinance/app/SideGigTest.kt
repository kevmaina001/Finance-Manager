package com.myfinance.app

import com.myfinance.app.data.Cat
import com.myfinance.app.data.Repo
import com.myfinance.app.data.Snapshot
import com.myfinance.app.model.PendingPay
import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Test

class SideGigTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    private fun rx(id: String, amount: Double, from: String, t: Long) = Tx(id, t, TxType.RECEIVED, amount, from, from)
    private fun snap(vararg txs: Tx) = Snapshot(txs.toList(), txs.toList(), emptyList(), emptyList(), null, null, t0)

    @Test
    fun pendingPayIsClearedByPayerMoney() {
        val s = snap(rx("A", 20_000.0, "MARY WANJIKU", t0 + day), rx("B", 10_000.0, "MARY WANJIKU", t0 + 3 * day), rx("C", 500.0, "PETER", t0 + day))
        val p = PendingPay("p", "Website", "MARY", 50_000.0, t0 + 5 * day, t0)
        val r = Repo.rebuild(s, emptyMap(), emptyMap(), emptyList(), pending = listOf(p))
        assertEquals(20_000.0, r.pending.single().second, 0.01)
        assertEquals(listOf("Website", "Website"), r.txs.filter { it.category == Cat.SIDE }.map { it.account })
        assertEquals(Cat.RECEIVED, r.txs.first { it.id == "C" }.category)
    }

    @Test
    fun taggedMoneyCountsForThatGig() {
        val s = snap(rx("A", 7_000.0, "JOHN DOE", t0 + day))
        val p = PendingPay("p", "Fibre install", "", 7_000.0, t0 + 2 * day, t0)
        val r = Repo.rebuild(s, emptyMap(), emptyMap(), emptyList(), gigTags = mapOf("A" to "Fibre install"), pending = listOf(p))
        assertEquals(0.0, r.pending.single().second, 0.01)
        assertEquals(Cat.SIDE, r.txs.single().category)
    }

    @Test
    fun alwaysFromSenderAndNotAGig() {
        val s = snap(rx("A", 3_000.0, "JANE", t0), rx("B", 4_000.0, "JANE", t0 + day))
        // "always" rule for JANE, but B was answered "not a gig"
        val r = Repo.rebuild(s, emptyMap(), mapOf("B" to Cat.RECEIVED), emptyList(), gigSenders = mapOf("JANE" to "Design"))
        assertEquals("Design", r.txs.first { it.id == "A" }.account)
        assertEquals(Cat.SIDE, r.txs.first { it.id == "A" }.category)
        assertEquals(Cat.RECEIVED, r.txs.first { it.id == "B" }.category)
        // untagged list skips answered ones and tagged ones
        assertEquals(0, Repo.untaggedIncome(r, mapOf("B" to Cat.RECEIVED), emptyMap(), 0).size)
    }
}
