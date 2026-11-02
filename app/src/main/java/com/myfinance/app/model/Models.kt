package com.myfinance.app.model

/** A raw SMS from the inbox. */
data class Sms(val time: Long, val address: String, val body: String)

enum class TxType {
    RECEIVED,   // money in from a person/business/bank
    SENT,       // send money to a person
    PAYBILL,    // paybill / buy goods with account number
    TILL,       // buy goods (till)
    CARD,       // M-PESA virtual card (Uber, Netflix, ...)
    WITHDRAW,   // cash withdrawal at agent
    DEPOSIT,    // cash deposit at agent (own money, not income)
    BANK_SENT,  // bank (e.g. Equity) -> someone else's M-Pesa
    RIDE,       // Uber/Bolt trip from a receipt notification (paid cash/card, not in SMS)
    LOAN_IN,    // money borrowed
    LOAN_REPAY, // money paid back to a lender
    FEE,        // loan fees / interest
    INTERNAL,   // moves between own accounts (Pochi <-> M-Pesa, Equity <-> M-Pesa)
}

data class Tx(
    val id: String,
    val time: Long,
    val type: TxType,
    val amount: Double,
    val counterparty: String,
    val account: String = "",
    val fee: Double = 0.0,          // M-Pesa transaction cost
    val wallet: String = "M-PESA",  // M-PESA, Pochi, Equity, ...
    val ref: String = "",
    val raw: String = "",
    val category: String = "",
)

enum class LoanOp { SET, ADD, REDUCE, CLEAR, DUE, FULIZA_DRAW }

data class LoanEvent(
    val time: Long,
    val lender: String,
    val op: LoanOp,
    val amount: Double = 0.0,
    val due: Long? = null,
    val draw: Double = 0.0,
    val fee: Double = 0.0,
)

data class LoanStatus(
    val lender: String,
    val outstanding: Double,
    val due: Long?,
    val lastUpdate: Long,
)

data class ParseResult(
    val txs: List<Tx>,
    val loanEvents: List<LoanEvent>,
    val mpesaBalance: Pair<Long, Double>?,
    val pochiBalance: Pair<Long, Double>?,
)

/** A loan that doesn't send SMS (e.g. a private lender). */
data class ManualLoan(
    val id: String,
    val lender: String,
    val balance: Double,   // balance as of [asOf]
    val asOf: Long,
    val monthly: Double,
    val dueDay: Int,       // day of month the instalment is due
    val matchName: String, // payments to a counterparty containing this reduce the balance
    val principalPart: Double = 0.0, // part of each instalment that reduces the loan (0 = the whole payment)
    val interestPart: Double = 0.0,
    val savingsPart: Double = 0.0,   // part of each instalment that goes to savings
    val savingsBalance: Double = 0.0, // savings held with this lender as of [asOf]
    val otherSavingsBalance: Double = 0.0, // a second savings account fed by the rest of each instalment, as of [asOf]
) {
    /** What's left of each instalment after principal, interest and loan-account savings (goes to the second account). */
    val otherPart get() = if (principalPart > 0) maxOf(0.0, monthly - principalPart - interestPart - savingsPart) else 0.0
}

/**
 * A bank account whose balance the user sets by hand; afterwards the app follows it from SMS:
 * + M-Pesa sent to [accountNo], − money the bank sends out (to M-Pesa or other people) and bank loan repayments.
 */
data class BankAccount(
    val id: String,
    val name: String,       // also the Tx.wallet the parser uses for this bank, e.g. "Equity"
    val accountNo: String,
    val balance: Double,
    val asOf: Long,
)

/** Money someone owes you. */
data class Receivable(
    val id: String,
    val person: String,
    val amount: Double,     // principal they owe
    val interest: Double,   // extra agreed on top
    val due: Long,
    val matchName: String,  // money received from a counterparty containing this counts as repayment
    val asOf: Long,         // only money received after this counts
) {
    val total get() = amount + interest
}

/**
 * Side-gig work that's done (or agreed) but not paid yet.
 * Money in that is tagged with the same [gig], or comes from someone whose name contains [payer], pays it off.
 */
data class PendingPay(
    val id: String,
    val gig: String,          // e.g. "Website for Mary", "Fibre install"
    val payer: String,        // name as it appears on M-Pesa (blank = only tagged money counts)
    val amount: Double,
    val due: Long,            // when you expect the money
    val asOf: Long,           // only money received after this counts
    val paidOther: Double = 0.0, // paid in cash / outside SMS
    val note: String = "",
)

data class StockBatch(
    val id: String,
    val name: String,
    val cost: Double,
    val fundedBy: String,
    val bought: Long,
    val expectedPrice: Double,
    val expectedSale: String,
    val soldAmount: Double = 0.0,
    val soldDate: Long = 0L,
    val extraCosts: Double = 0.0,
) {
    val sold get() = soldAmount > 0
    val expectedProfit get() = if (expectedPrice > 0) expectedPrice - cost - extraCosts else 0.0
    val realizedProfit get() = if (sold) soldAmount - cost - extraCosts else 0.0
}

/** level: 0 = good, 1 = info, 2 = warning, 3 = urgent */
/** [tag]: "due" = the 7-day payment list, "longterm" = long-term loans/savings (shown on Loans, not Home). */
data class Advice(val level: Int, val title: String, val body: String, val tag: String = "")
