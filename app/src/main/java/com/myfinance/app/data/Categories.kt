package com.myfinance.app.data

import com.myfinance.app.model.Tx
import com.myfinance.app.model.TxType

object Cat {
    const val SALARY = "Salary"
    const val SIDE = "Side income"
    const val BIZ_SALES = "Business sales"
    const val RECEIVED = "Received from people"
    const val BORROWED = "Borrowed (personal loan)"
    const val REPAID_TO_ME = "Debt repaid to me"
    const val LENT = "Lent out"

    const val RENT = "Rent"
    const val FOOD = "Food & groceries"
    const val EATOUT = "Eating out"
    const val FUEL = "Fuel & transport"
    const val POWER = "Electricity"
    const val NET = "Internet"
    const val DATA = "Data & airtime"
    const val SUBS = "Subscriptions"
    const val FAMILY = "Family & friends"
    const val GOING_OUT = "Going out"
    const val CLOTHES = "Clothes & shopping"
    const val STOCK = "Business stock"
    const val BIZCOST = "Business costs"
    const val BET = "Betting"
    const val CASH = "Cash withdrawal"
    const val OTHER = "Other spending"
    const val LOANPAY = "Loan repayment"
    const val SAVINGS = "Savings"
    /** Money moved between your own accounts (M-Pesa <-> Equity etc.): never spending or income. */
    const val TRANSFER = "Own transfer"

    val income = listOf(SALARY, SIDE, BIZ_SALES, RECEIVED, BORROWED, REPAID_TO_ME, TRANSFER)
    val expense = listOf(
        RENT, FOOD, EATOUT, FUEL, POWER, NET, DATA, SUBS, FAMILY, GOING_OUT,
        CLOTHES, STOCK, BIZCOST, BET, CASH, OTHER, LOANPAY, SAVINGS, LENT, TRANSFER,
    )
    /** Money in that isn't earnings, and money out that isn't spending. */
    val notIncome = setOf(BORROWED, REPAID_TO_ME, TRANSFER)
    val notSpending = setOf(SAVINGS, LENT, TRANSFER)
    /** Categories paid out of the weekly allowance. */
    val weekly = setOf(FOOD, EATOUT, FUEL, FAMILY, GOING_OUT, DATA)
    val business = setOf(STOCK, BIZCOST, BIZ_SALES)

    /** Substring of "COUNTERPARTY | ACCOUNT" (upper case) -> category. First match wins. */
    val defaultRules: List<Pair<String, String>> = listOf(
        "KPLC" to POWER,
        "KENYA POWER" to POWER,
        "KONNECT" to NET,
        "4026909" to NET,
        "SAFARICOM DATA" to DATA,
        "POSTPAID BUNDLES" to DATA,
        "SAFARICOM OFFERS" to DATA,
        "TUNUKIWA" to DATA,
        "AIRTIME" to DATA,
        "UBER * EATS" to EATOUT,
        "UBER *EATS" to EATOUT,
        "GLOVO" to EATOUT,
        "NETFLIX" to SUBS,
        "YOUTUBE" to SUBS,
        "UBER *ONE" to SUBS,
        "LINKEDIN" to SUBS,
        "GOOGLE" to SUBS,
        "SPOTIFY" to SUBS,
        "SHOWMAX" to SUBS,
        "FACEBOOK ADS" to BIZCOST,
        "TINGG" to BIZCOST,
        "SERVICE STATION" to FUEL,
        "PETROLEUM" to FUEL,
        "SHELL" to FUEL,
        "RUBIS" to FUEL,
        "TOTALENERGIES" to FUEL,
        "UBER *TRIP" to FUEL,
        "BOLT" to FUEL,
        "MADARAKA EXPRESS" to FUEL,
        "WEALTH MANAGEMENT" to SAVINGS,
        "ZIIDI" to SAVINGS,
        "M-SHWARI LOCK" to SAVINGS,
        "BUTCHERY" to FOOD,
        "CAFETERIA" to FOOD,
        "ROASTERS" to FOOD,
        "SUPERMARKET" to FOOD,
        "NAIVAS" to FOOD,
        "QUICK MART" to FOOD,
        "QUICKMART" to FOOD,
        "CARREFOUR" to FOOD,
        "MINI-SHOP" to FOOD,
        "MIN-SHOP" to FOOD,
        "GROCER" to FOOD,
        "RESTAURANT" to GOING_OUT,
        "CHILL" to GOING_OUT,
        "LOUNGE" to GOING_OUT,
        " BAR" to GOING_OUT,
        "CLUB" to GOING_OUT,
        "SPORTPESA" to BET,
        "ODIBETS" to BET,
        "BETIKA" to BET,
        "MOZZART" to BET,
        "JUMIA" to CLOTHES,
        "KILIMALL" to CLOTHES,
    )

    private val incomeTypes = setOf(TxType.RECEIVED)
    val spendTypes = setOf(TxType.SENT, TxType.PAYBILL, TxType.TILL, TxType.CARD, TxType.WITHDRAW, TxType.BANK_SENT, TxType.RIDE)
}

class Categorizer(
    private val userRules: Map<String, String>,
    private val txOverrides: Map<String, String>,
    private val keywordRules: Map<String, String> = emptyMap(),
) {
    fun categorize(tx: Tx): String {
        txOverrides[tx.id]?.let { return it }
        if (tx.type == TxType.INTERNAL) return Cat.TRANSFER
        val isIncome = tx.type == TxType.RECEIVED || tx.type == TxType.DEPOSIT
        val isSpend = tx.type in Cat.spendTypes
        if (!isIncome && !isSpend) return ""
        if (tx.type == TxType.DEPOSIT) return "" // own cash, unless the user tagged it (e.g. a loan handed over in cash)
        val key = (tx.counterparty + " | " + tx.account).uppercase()
        userRules[tx.counterparty.uppercase()]?.let { return it }
        for ((k, v) in keywordRules) {
            if (key.contains(k.uppercase()) && ((isIncome && v in Cat.income) || (isSpend && v in Cat.expense))) return v
        }
        for ((k, v) in Cat.defaultRules) {
            if (key.contains(k) && ((isIncome && v in Cat.income) || (isSpend && v in Cat.expense))) return v
        }
        return when (tx.type) {
            TxType.RECEIVED -> if (tx.wallet == "Pochi") Cat.BIZ_SALES else Cat.RECEIVED
            TxType.SENT, TxType.BANK_SENT -> Cat.FAMILY
            TxType.CARD -> Cat.SUBS
            TxType.WITHDRAW -> Cat.CASH
            TxType.RIDE -> Cat.FUEL
            else -> Cat.OTHER
        }
    }
}
