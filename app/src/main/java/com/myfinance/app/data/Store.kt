package com.myfinance.app.data

import android.content.Context
import com.myfinance.app.model.BankAccount
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.PendingPay
import com.myfinance.app.model.Receivable
import com.myfinance.app.model.StockBatch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Everything the user sets up by hand, kept on the phone in SharedPreferences. */
class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("myfinance", Context.MODE_PRIVATE)

    var ownerName: String
        get() = p.getString("owner", DEFAULT_OWNER) ?: DEFAULT_OWNER
        set(v) = p.edit().putString("owner", v).apply()

    var weeklyAllowance: Double
        get() = p.getFloat("weekly", 5000f).toDouble()
        set(v) = p.edit().putFloat("weekly", v.toFloat()).apply()

    /** Blur the balance cards (for opening the app in front of people). Remembered between opens. */
    var hideBalances: Boolean
        get() = p.getBoolean("hideBalances", false)
        set(v) = p.edit().putBoolean("hideBalances", v).apply()

    /** Ask for fingerprint / phone PIN to open the app. */
    var appLock: Boolean
        get() = p.getBoolean("appLock", true)
        set(v) = p.edit().putBoolean("appLock", v).apply()

    var dailySummary: Boolean
        get() = p.getBoolean("dailySummary", true)
        set(v) = p.edit().putBoolean("dailySummary", v).apply()

    var budgets: Map<String, Double>
        get() = p.getString("budgets", null)?.let { s ->
            val o = JSONObject(s); o.keys().asSequence().associateWith { o.getDouble(it) }
        } ?: DEFAULT_BUDGETS
        set(v) = p.edit().putString("budgets", JSONObject(v).toString()).apply()

    var userRules: Map<String, String>
        get() = readStrMap("rules")
        set(v) = writeStrMap("rules", v)

    /** Text in "COUNTERPARTY | ACCOUNT" (upper case) -> category, e.g. a rent paybill account number -> Rent. */
    var keywordRules: Map<String, String>
        get() = readStrMap("keywordRules")
        set(v) = writeStrMap("keywordRules", v)

    var txOverrides: Map<String, String>
        get() = readStrMap("overrides")
        set(v) = writeStrMap("overrides", v)

    var manualLoans: List<ManualLoan>
        get() = readArr("manualLoans") { o ->
            ManualLoan(
                o.getString("id"), o.getString("lender"), o.getDouble("balance"), o.getLong("asOf"),
                o.getDouble("monthly"), o.getInt("dueDay"), o.optString("matchName"),
                o.optDouble("principalPart", 0.0), o.optDouble("interestPart", 0.0),
                o.optDouble("savingsPart", 0.0), o.optDouble("savingsBalance", 0.0), o.optDouble("otherSavingsBalance", 0.0),
            )
        }
        set(v) = writeArr("manualLoans", v) { l ->
            JSONObject().put("id", l.id).put("lender", l.lender).put("balance", l.balance).put("asOf", l.asOf)
                .put("monthly", l.monthly).put("dueDay", l.dueDay).put("matchName", l.matchName)
                .put("principalPart", l.principalPart).put("interestPart", l.interestPart)
                .put("savingsPart", l.savingsPart).put("savingsBalance", l.savingsBalance)
                .put("otherSavingsBalance", l.otherSavingsBalance)
        }

    var receivables: List<Receivable>
        get() = readArr("receivables") { o ->
            Receivable(
                o.getString("id"), o.getString("person"), o.getDouble("amount"), o.optDouble("interest", 0.0),
                o.getLong("due"), o.optString("matchName"), o.getLong("asOf"),
            )
        }
        set(v) = writeArr("receivables", v) { r ->
            JSONObject().put("id", r.id).put("person", r.person).put("amount", r.amount).put("interest", r.interest)
                .put("due", r.due).put("matchName", r.matchName).put("asOf", r.asOf)
        }

    /** Money-in transaction id -> side gig name. */
    var gigTags: Map<String, String>
        get() = readStrMap("gigTags")
        set(v) = writeStrMap("gigTags", v)

    /** Sender (upper case) -> side gig name: all money from them counts as that gig. */
    var gigSenders: Map<String, String>
        get() = readStrMap("gigSenders")
        set(v) = writeStrMap("gigSenders", v)

    /** Gig names the user has used, most recent first. */
    val gigNames: List<String>
        get() = p.getString("gigNames", null)?.let { s -> val a = JSONArray(s); (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun rememberGig(name: String) {
        val a = JSONArray(); (listOf(name) + gigNames.filter { !it.equals(name, true) }).take(12).forEach { a.put(it) }
        p.edit().putString("gigNames", a.toString()).apply()
    }

    /** Tag one money-in transaction as side-gig income (optionally everything from that sender). */
    fun tagGig(txId: String, sender: String, gig: String, always: Boolean) {
        gigTags = gigTags + (txId to gig)
        txOverrides = txOverrides - txId
        if (always && sender.isNotBlank()) gigSenders = gigSenders + (sender.uppercase() to gig)
        rememberGig(gig)
    }

    /** "Not a side gig": keep it as money from people and stop asking. */
    fun untagGig(txId: String) {
        gigTags = gigTags - txId
        txOverrides = txOverrides + (txId to Cat.RECEIVED)
    }

    var banks: List<BankAccount>
        get() = readArr("banks") { o ->
            BankAccount(o.getString("id"), o.getString("name"), o.optString("accountNo"), o.getDouble("balance"), o.getLong("asOf"))
        }
        set(v) = writeArr("banks", v) { b ->
            JSONObject().put("id", b.id).put("name", b.name).put("accountNo", b.accountNo).put("balance", b.balance).put("asOf", b.asOf)
        }

    /** Paybill account numbers that are the user's own (money sent there is a transfer, not spending). */
    var ownAccounts: Set<String>
        get() = p.getStringSet("ownAccounts", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("ownAccounts", v).apply()

    var pendingPays: List<PendingPay>
        get() = readArr("pendingPays") { o ->
            PendingPay(
                o.getString("id"), o.getString("gig"), o.optString("payer"), o.getDouble("amount"), o.getLong("due"),
                o.getLong("asOf"), o.optDouble("paidOther", 0.0), o.optString("note"),
            )
        }
        set(v) = writeArr("pendingPays", v) { x ->
            JSONObject().put("id", x.id).put("gig", x.gig).put("payer", x.payer).put("amount", x.amount).put("due", x.due)
                .put("asOf", x.asOf).put("paidOther", x.paidOther).put("note", x.note)
        }

    /** Ids we've already sent a "was this a side gig?" notification for. */
    fun firstAsk(txId: String): Boolean {
        val seen = p.getStringSet("gigAsked", emptySet()) ?: emptySet()
        if (txId in seen) return false
        p.edit().putStringSet("gigAsked", (seen.toList().takeLast(200) + txId).toSet()).apply()
        return true
    }

    var stock: List<StockBatch>
        get() = readArr("stock") { o ->
            StockBatch(
                o.getString("id"), o.getString("name"), o.getDouble("cost"), o.optString("fundedBy"),
                o.getLong("bought"), o.optDouble("expectedPrice", 0.0), o.optString("expectedSale"),
                o.optDouble("soldAmount", 0.0), o.optLong("soldDate", 0L), o.optDouble("extraCosts", 0.0),
            )
        }
        set(v) = writeArr("stock", v) { b ->
            JSONObject().put("id", b.id).put("name", b.name).put("cost", b.cost).put("fundedBy", b.fundedBy)
                .put("bought", b.bought).put("expectedPrice", b.expectedPrice).put("expectedSale", b.expectedSale)
                .put("soldAmount", b.soldAmount).put("soldDate", b.soldDate).put("extraCosts", b.extraCosts)
        }

    /** Uber/Bolt trips captured from notifications (kept for ~6 months). */
    var rides: List<Ride>
        get() = readArr("rides") { o ->
            Ride(o.getString("id"), o.getLong("time"), o.getString("provider"), o.getDouble("amount"), o.optString("source"), o.optString("text"), o.optString("driver"))
        }
        set(v) {
            val cutoff = System.currentTimeMillis() - 200L * 86_400_000L
            writeArr("rides", v.filter { it.time > cutoff }.sortedBy { it.time }) { r ->
                JSONObject().put("id", r.id).put("time", r.time).put("provider", r.provider).put("amount", r.amount)
                    .put("source", r.source).put("text", r.text).put("driver", r.driver)
            }
        }

    /** Last few Uber/Bolt notifications seen (captured or ignored), to check the capture works. */
    val rideLog: List<String>
        get() = p.getString("rideLog", null)?.let { s -> val a = JSONArray(s); (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun logRide(line: String) {
        val a = JSONArray(); (listOf(line) + rideLog).take(15).forEach { a.put(it) }
        p.edit().putString("rideLog", a.toString()).apply()
    }

    /** True the first time [key] is seen today; used so each reminder fires once a day. */
    fun firstTimeToday(key: String): Boolean {
        val c = Calendar.getInstance()
        val k = "n:$key:${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
        if (p.getBoolean(k, false)) return false
        p.edit().putBoolean(k, true).apply()
        return true
    }

    // ---------------------------------------------------------------- backup / restore
    var lastBackup: Long
        get() = p.getLong("lastBackup", 0L)
        set(v) = p.edit().putLong("lastBackup", v).apply()

    /** Everything the app has saved (loans, debts, gigs, rules, balances, settings) as one JSON file. */
    fun exportJson(): String {
        val prefs = JSONObject()
        p.all.toSortedMap().forEach { (k, v) ->
            if (k.startsWith("n:")) return@forEach // "already notified today" markers
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            prefs.put(k, e)
        }
        return JSONObject().put("app", BACKUP_TAG).put("format", 1).put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefs).toString(2)
    }

    /** Replaces everything with a backup. Returns false (and changes nothing) if the file isn't a My Finance backup. */
    fun importJson(text: String): Boolean {
        val root = try { JSONObject(text) } catch (e: Exception) { return false }
        if (root.optString("app") != BACKUP_TAG) return false
        val prefs = root.optJSONObject("prefs") ?: return false
        val e = p.edit().clear()
        prefs.keys().forEach { k ->
            val o = prefs.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> e.putString(k, o.getString("v"))
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "set" -> o.getJSONArray("v").let { a -> e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        return e.commit()
    }

    /**
     * One-time setup from `assets/personal.json` if the build has one (it's git-ignored, so public builds start blank).
     * Only fills in what isn't saved on the phone yet — it never overwrites the user's data.
     */
    fun seedIfNeeded() {
        if (p.getInt("seedV", 1) >= SEED_VERSION) return
        val o = try {
            ctx.assets.open("personal.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) { null }
        if (o != null) {
            val e = p.edit()
            if (!p.contains("owner") && o.has("owner")) e.putString("owner", o.getString("owner"))
            if (!p.contains("weekly") && o.has("weeklyAllowance")) e.putFloat("weekly", o.getDouble("weeklyAllowance").toFloat())
            if (!p.contains("budgets")) o.optJSONObject("budgets")?.let { e.putString("budgets", it.toString()) }
            e.apply()
            o.optJSONObject("keywordRules")?.let { r -> keywordRules = r.keys().asSequence().associateWith { r.getString(it) } + keywordRules }
            o.optJSONArray("ownAccounts")?.let { a -> ownAccounts = ownAccounts + (0 until a.length()).map { a.getString(it) } }
        }
        p.edit().putInt("seedV", SEED_VERSION).putBoolean("seeded", true).apply()
    }

    private fun readStrMap(key: String): Map<String, String> =
        p.getString(key, null)?.let { s -> val o = JSONObject(s); o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()

    private fun writeStrMap(key: String, v: Map<String, String>) =
        p.edit().putString(key, JSONObject(v as Map<*, *>).toString()).apply()

    private fun <T> readArr(key: String, f: (JSONObject) -> T): List<T> =
        p.getString(key, null)?.let { s -> val a = JSONArray(s); (0 until a.length()).map { f(a.getJSONObject(it)) } } ?: emptyList()

    private fun <T> writeArr(key: String, v: List<T>, f: (T) -> JSONObject) {
        val a = JSONArray(); v.forEach { a.put(f(it)) }
        p.edit().putString(key, a.toString()).apply()
    }

    companion object {
        /** Bump when [seedIfNeeded] learns something new to copy from personal.json. */
        private const val SEED_VERSION = 7
        private const val BACKUP_TAG = "myfinance-backup"
        /** Set in Budget → Settings (or personal.json). Used to recognise transfers between your own accounts. */
        const val DEFAULT_OWNER = ""
        val DEFAULT_BUDGETS: Map<String, Double> = linkedMapOf(
            Cat.RENT to 10_000.0,
            Cat.FOOD to 7_500.0,
            Cat.EATOUT to 1_500.0,
            Cat.FUEL to 3_000.0,
            Cat.POWER to 2_400.0,
            Cat.NET to 2_000.0,
            Cat.DATA to 1_000.0,
            Cat.SUBS to 700.0,
            Cat.FAMILY to 4_000.0,
            Cat.GOING_OUT to 2_000.0,
            Cat.CLOTHES to 0.0,
            Cat.BET to 0.0,
        )
    }
}
