package com.myfinance.app

import com.myfinance.app.data.Cat
import com.myfinance.app.data.Repo
import com.myfinance.app.logic.Analytics
import com.myfinance.app.model.Sms
import com.myfinance.app.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Made-up SMS in the real M-Pesa / Equity formats. */
class OwnTransferTest {
    private val owner = "JOHN KAMAU DOE"
    private val t = 1_790_881_128_016L
    private val sms = listOf(
        // M-Pesa -> own bank account, confirmed by the bank
        Sms(t, "MPESA", "AB1CD23EF4 Confirmed. Ksh60,000.00 sent to Equity Paybill Account for account 1111222233334 on 1/10/26 at 9:58 PM New M-PESA balance is Ksh0.00. Transaction cost, Ksh108.00."),
        Sms(t + 1_000, "Equity Bank", "Dear John Kamau Doe, Your transaction of KES. 60,000.00 has successfully been deposited to Equity Account in favor of JOHN KAMAU DOE Ref. Number AB1CD23EF4 on 01-10-2026 at 21:58. Thank you."),
        // same account later, but the bank sent no SMS
        Sms(t + 6 * 86_400_000L, "MPESA", "AB7CD23EF5 Confirmed. Ksh20,000.00 sent to Equity Paybill Account for account 1111222233334 on 7/10/26 at 2:50 AM New M-PESA balance is Ksh760.24. Transaction cost, Ksh62.00."),
        // rent: same bank, different account -> still spending
        Sms(t + 7 * 86_400_000L, "MPESA", "AB8CD23EF6 Confirmed. Ksh10,000.00 sent to Equity Paybill Account for account 0999888777666 on 8/10/26 at 9:00 AM New M-PESA balance is Ksh100.00. Transaction cost, Ksh57.00."),
    )

    @Test
    fun learnedOwnAccountIsATransfer() {
        val s = Repo.build(sms, null, t + 8 * 86_400_000L, owner)
        val byRef = s.txs.associateBy { it.ref }
        assertEquals(TxType.INTERNAL, byRef.getValue("AB1CD23EF4").type)
        assertEquals(TxType.INTERNAL, byRef.getValue("AB7CD23EF5").type)
        assertEquals(Cat.TRANSFER, byRef.getValue("AB7CD23EF5").category)
        assertEquals(TxType.PAYBILL, byRef.getValue("AB8CD23EF6").type)
        val m = Analytics.month(s.txs, 0, Long.MAX_VALUE)
        assertEquals(10_000.0, m.spending, 0.01) // only the rent
        assertEquals(0.0, m.moneyIn, 0.01)
    }

    @Test
    fun equityReceivedViaMpesaFormat() {
        val n = listOf(
            Sms(t, "MPESA", "AB9CD23EF7 Confirmed. Ksh4,000.00 sent to Equity Paybill Account for account 5555666677778 on 1/11/26 at 10:21 PM New M-PESA balance is Ksh567.52. Transaction cost, Ksh34.00."),
            Sms(t + 1_000, "Equity Bank", "Confirmed. KES. 4,000.00 from John Kamau Doe received via M-Pesa Ref. AB9CD23EF7 on 01-11-2026 at 22:21. Thank you."),
        )
        assertEquals(TxType.INTERNAL, Repo.build(n, null, t, owner).txs.single().type)
    }

    @Test
    fun markedTransferIsNotSpending() {
        val base = Repo.build(sms.takeLast(1), null, t + 8 * 86_400_000L, owner)
        val tx = base.txs.single()
        val s = Repo.rebuild(base, emptyMap(), mapOf(tx.id to Cat.TRANSFER), emptyList())
        assertEquals(0.0, Analytics.month(s.txs, 0, Long.MAX_VALUE).spending, 0.01)
    }

    @Test
    fun keywordRuleOnAccountNumber() {
        val base = Repo.build(sms.takeLast(1), null, t + 8 * 86_400_000L, owner)
        val s = Repo.rebuild(base, emptyMap(), emptyMap(), emptyList(), keywordRules = mapOf("0999888777666" to Cat.RENT))
        assertEquals(Cat.RENT, s.txs.single().category)
    }
}
