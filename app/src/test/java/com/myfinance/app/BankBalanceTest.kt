package com.myfinance.app

import com.myfinance.app.data.Repo
import com.myfinance.app.model.BankAccount
import com.myfinance.app.model.Sms
import org.junit.Assert.assertEquals
import org.junit.Test

/** Made-up SMS in the real M-Pesa / Equity formats. */
class BankBalanceTest {
    private val t = 1_793_560_878_558L
    private val acct = "1111222233334"
    private val eq = BankAccount("equity", "Equity", acct, 4_000.0, t + 60_000)

    @Test
    fun followsMovementsAfterTheBalanceWasSet() {
        val h = 3_600_000L
        val sms = listOf(
            // before the balance was set: already included in the 4,000
            Sms(t, "MPESA", "AB1CD23EF4 Confirmed. Ksh4,000.00 sent to Equity Paybill Account for account $acct on 1/11/26 at 10:21 PM New M-PESA balance is Ksh567.52. Transaction cost, Ksh34.00."),
            // +6,000 in
            Sms(t + h, "MPESA", "AB2CD23EF5 Confirmed. Ksh6,000.00 sent to Equity Paybill Account for account $acct on 2/11/26 at 9:00 AM New M-PESA balance is Ksh100.00. Transaction cost, Ksh57.00."),
            // −1,500 bank -> own M-Pesa
            Sms(t + 2 * h, "MPESA", "AB3CD23EF6 Confirmed.You have received Ksh1,500.00 from Equity Bulk Account 300600 on 2/11/26 at 10:00 AM New M-PESA balance is Ksh1,600.00."),
            Sms(t + 2 * h + 5_000, "Equity Bank", "Your transaction of Kshs. 1500.0 has been credited to 254700000001 John Kamau Doe. Ref. EQA0001. MPESA Ref. AB3CD23EF6 on 02/11/2026 at 10:00:00."),
            // −2,000 bank -> someone else
            Sms(t + 3 * h, "Equity Bank", "Your transaction of Kshs. 2000.0 has been credited to 254700000002 JANE WAMBUI SMITH. Ref. EQA0002. MPESA Ref. AB4CD23EF7 on 02/11/2026 at 11:00:00."),
        )
        val s = Repo.build(sms, null, t + 4 * h, "JOHN KAMAU DOE")
        assertEquals(6_500.0, Repo.bankBalance(eq, s.txs), 0.01)
    }
}
