package com.myfinance.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myfinance.app.data.Repo
import com.myfinance.app.data.Snapshot
import com.myfinance.app.data.Store
import com.myfinance.app.model.ManualLoan
import com.myfinance.app.model.StockBatch
import com.myfinance.app.model.Tx
import com.myfinance.app.work.DailySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVM(app: Application) : AndroidViewModel(app) {
    val store = Store(app).also { it.seedIfNeeded() }

    var snap by mutableStateOf<Snapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var budgets by mutableStateOf(store.budgets); private set
    var weekly by mutableStateOf(store.weeklyAllowance); private set
    var stock by mutableStateOf(store.stock); private set
    var ownerName by mutableStateOf(store.ownerName); private set

    fun refresh() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                snap = withContext(Dispatchers.Default) { Repo.load(getApplication(), store) }
                gigNames = store.gigNames
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            loading = false
        }
    }

    private fun rebuild() {
        val s = snap ?: return
        viewModelScope.launch { snap = withContext(Dispatchers.Default) { Repo.rebuild(s, store) } }
    }

    fun setCategory(tx: Tx, cat: String, applyToAll: Boolean) {
        val acct = tx.account.trim()
        if (cat == com.myfinance.app.data.Cat.TRANSFER && applyToAll && acct.length >= 5 && acct.any { it.isDigit() }) {
            // e.g. "Equity Paybill, account 1180..." — remember the account, not the bank (rent also goes to Equity)
            store.ownAccounts = store.ownAccounts + acct
            store.txOverrides = store.txOverrides + (tx.id to cat)
            refresh()
            return
        }
        if (applyToAll && tx.type == com.myfinance.app.model.TxType.PAYBILL && acct.length >= 5 && acct.any { it.isDigit() }) {
            // e.g. rent paid to "Equity Paybill, account 0123..." — the rule goes on the account, not the whole bank
            store.keywordRules = store.keywordRules + (acct.uppercase() to cat)
            store.txOverrides = store.txOverrides - tx.id
        } else if (applyToAll) {
            store.userRules = store.userRules + (tx.counterparty.uppercase() to cat)
            // a rule should win over older one-off overrides for the same person
            val ids = snap?.txs?.filter { it.counterparty.equals(tx.counterparty, true) }?.map { it.id }?.toSet().orEmpty()
            store.txOverrides = store.txOverrides.filterKeys { it !in ids }
        } else {
            store.txOverrides = store.txOverrides + (tx.id to cat)
        }
        rebuild()
    }

    fun setBudget(cat: String, limit: Double) {
        store.budgets = budgets + (cat to limit); budgets = store.budgets
    }

    fun changeWeekly(v: Double) {
        store.weeklyAllowance = v; weekly = v
    }

    fun setOwner(v: String) {
        store.ownerName = v; ownerName = v; refresh()
    }

    var dailySummary by mutableStateOf(store.dailySummary); private set

    var hideBalances by mutableStateOf(store.hideBalances); private set

    fun toggleHide() {
        hideBalances = !hideBalances; store.hideBalances = hideBalances
    }

    // ---------------------------------------------------------------- app lock
    var lockEnabled by mutableStateOf(store.appLock); private set
    /** Starts locked; kept in the ViewModel so rotating the phone doesn't ask again. */
    var locked by mutableStateOf(store.appLock)
    var backgroundAt = 0L

    fun setLock(on: Boolean) {
        store.appLock = on; lockEnabled = on
    }

    fun toggleDailySummary(on: Boolean) {
        store.dailySummary = on; dailySummary = on
        DailySummary.schedule(getApplication())
    }

    /** Whether the user has allowed notification access (needed for Uber/Bolt receipts). */
    fun rideAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(getApplication()).contains(getApplication<Application>().packageName)

    fun rideLog(): List<String> = store.rideLog

    /** One-off sends from the last 60 days that might have been ride payments. */
    fun possibleRides(): List<Tx> {
        val s = snap ?: return emptyList()
        return Repo.possibleRides(s.txs, store.userRules, store.txOverrides, System.currentTimeMillis() - 60L * 86_400_000L)
    }

    /** Answer to "was this a ride?" — applies to this payment only, since drivers are rarely paid twice. */
    fun answerRide(tx: Tx, wasRide: Boolean) = setCategory(tx, if (wasRide) com.myfinance.app.data.Cat.FUEL else com.myfinance.app.data.Cat.FAMILY, false)

    fun previewDailySummary() {
        viewModelScope.launch(Dispatchers.Default) { DailySummary.send(getApplication()) }
    }

    fun saveManualLoan(l: ManualLoan) {
        store.manualLoans = store.manualLoans.filter { it.id != l.id } + l; rebuild()
    }

    fun saveReceivable(r: com.myfinance.app.model.Receivable) {
        store.receivables = store.receivables.filter { it.id != r.id } + r; rebuild()
    }

    fun deleteReceivable(id: String) {
        store.receivables = store.receivables.filter { it.id != id }; rebuild()
    }

    fun deleteManualLoan(id: String) {
        store.manualLoans = store.manualLoans.filter { it.id != id }; rebuild()
    }

    // ---------------------------------------------------------------- side gigs
    var gigNames by mutableStateOf(store.gigNames); private set

    /** Set when a "was this a side gig?" notification is tapped: the app opens on the tag dialog. */
    var tagRequest by mutableStateOf<Tx?>(null)

    fun tag(tx: Tx, gig: String, always: Boolean) {
        store.tagGig(tx.id, tx.counterparty, gig.trim(), always)
        gigNames = store.gigNames; tagRequest = null; rebuild()
    }

    fun notGig(tx: Tx) {
        store.untagGig(tx.id); tagRequest = null; rebuild()
    }

    /** Money in from the last 14 days that might be side-gig pay and hasn't been answered yet. */
    fun untaggedIncome(): List<Tx> {
        val s = snap ?: return emptyList()
        return Repo.untaggedIncome(s, store.txOverrides, store.userRules, System.currentTimeMillis() - 14L * 86_400_000L)
    }

    fun savePending(p: com.myfinance.app.model.PendingPay) {
        store.pendingPays = store.pendingPays.filter { it.id != p.id } + p
        store.rememberGig(p.gig); gigNames = store.gigNames; rebuild()
    }

    fun deletePending(id: String) {
        store.pendingPays = store.pendingPays.filter { it.id != id }; rebuild()
    }

    /** The user checked their bank app: reset the balance from now on. */
    fun setBankBalance(id: String, amount: Double) {
        store.banks = store.banks.map { if (it.id == id) it.copy(balance = amount, asOf = System.currentTimeMillis()) else it }
        rebuild()
    }

    // ---------------------------------------------------------------- backup / restore
    var lastBackup by mutableStateOf(store.lastBackup); private set
    var backupMessage by mutableStateOf<String?>(null)

    fun backupTo(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            backupMessage = try {
                val now = System.currentTimeMillis()
                store.lastBackup = now
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.exportJson().toByteArray()) }
                lastBackup = now
                "Backup saved ✓"
            } catch (e: Exception) {
                "Couldn't save the backup: ${e.message}"
            }
        }
    }

    fun restoreFrom(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) { null }
            if (text == null || !store.importJson(text)) {
                backupMessage = "That file isn't a My Finance backup — nothing was changed."
                return@launch
            }
            withContext(Dispatchers.Main) {
                budgets = store.budgets; weekly = store.weeklyAllowance; stock = store.stock; ownerName = store.ownerName
                gigNames = store.gigNames; dailySummary = store.dailySummary; lockEnabled = store.appLock
                hideBalances = store.hideBalances; lastBackup = store.lastBackup
                DailySummary.schedule(getApplication())
                backupMessage = "Restored ✓ — your loans, debts, gigs and settings are back."
                refresh()
            }
        }
    }

    fun saveStock(b: StockBatch) {
        val list = store.stock
        store.stock = if (list.any { it.id == b.id }) list.map { if (it.id == b.id) b else it } else list + b
        stock = store.stock
    }

    fun deleteStock(id: String) {
        store.stock = store.stock.filter { it.id != id }; stock = store.stock
    }
}
