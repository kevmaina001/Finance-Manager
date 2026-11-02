@file:OptIn(ExperimentalMaterial3Api::class)

package com.myfinance.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.myfinance.app.ui.AppLock
import com.myfinance.app.ui.LockScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.background
import com.myfinance.app.ui.FinanceTheme
import com.myfinance.app.ui.Page
import com.myfinance.app.ui.BrandDark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.myfinance.app.ui.AppVM
import com.myfinance.app.ui.BudgetScreen
import com.myfinance.app.ui.BusinessScreen
import com.myfinance.app.ui.HomeScreen
import com.myfinance.app.ui.LoansScreen
import com.myfinance.app.ui.SpendingScreen
import com.myfinance.app.work.DailySummary
import com.myfinance.app.work.ReminderWorker

class MainActivity : FragmentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleGigIntent(intent)
        // keep balances out of the recent-apps preview
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContent {
            FinanceTheme {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleGigIntent(intent)
    }

    /** Tapped a "was this a side gig?" notification: open on the tag dialog. */
    private fun handleGigIntent(i: android.content.Intent?) {
        val id = i?.getStringExtra(com.myfinance.app.work.GigPrompt.EXTRA_TX) ?: return
        vm.tagRequest = com.myfinance.app.model.Tx(
            id, System.currentTimeMillis(), com.myfinance.app.model.TxType.RECEIVED,
            i.getDoubleExtra(com.myfinance.app.work.GigPrompt.EXTRA_AMOUNT, 0.0), i.getStringExtra(com.myfinance.app.work.GigPrompt.EXTRA_NAME) ?: "",
        )
        i.removeExtra(com.myfinance.app.work.GigPrompt.EXTRA_TX)
        androidx.core.app.NotificationManagerCompat.from(this).cancel(id.hashCode())
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.backgroundAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (vm.lockEnabled && vm.backgroundAt > 0 && System.currentTimeMillis() - vm.backgroundAt > AppLock.GRACE_MS) vm.locked = true
        // no fingerprint or screen lock on the phone: nothing to check against, so don't lock the user out
        if (vm.locked && !AppLock.available(this)) vm.locked = false
    }

    private var prompting = false

    private fun unlock() {
        if (prompting) return
        prompting = true
        AppLock.prompt(this) { ok -> prompting = false; if (ok) vm.locked = false }
    }

    override fun onResume() {
        super.onResume()
        if (hasSms()) vm.refresh()
    }

    private fun hasSms() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private data class Tab(val label: String, val icon: ImageVector)

    @Composable
    private fun App(vm: AppVM) {
        var granted by remember { mutableStateOf(hasSms()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            granted = res[Manifest.permission.READ_SMS] == true || hasSms()
            if (granted) vm.refresh()
        }
        LaunchedEffect(granted) {
            if (granted) {
                // existing installs: also ask for live SMS so side-gig money can be tagged as it arrives
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                    launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
                ReminderWorker.schedule(this@MainActivity)
                DailySummary.schedule(this@MainActivity)
            }
        }

        if (vm.locked) {
            LaunchedEffect(Unit) { unlock() }
            LockScreen { unlock() }
            return
        }

        if (!granted) {
            PermissionScreen {
                val perms = buildList {
                    add(Manifest.permission.READ_SMS)
                    add(Manifest.permission.RECEIVE_SMS)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                launcher.launch(perms.toTypedArray())
            }
            return
        }

        val tabs = listOf(
            Tab("Home", Icons.Filled.Home),
            Tab("Spending", Icons.Filled.List),
            Tab("Loans", Icons.Filled.Warning),
            Tab("Business", Icons.Filled.ShoppingCart),
            Tab("Budget", Icons.Filled.DateRange),
        )
        var tab by rememberSaveable { mutableIntStateOf(0) }
        androidx.compose.runtime.CompositionLocalProvider(
            com.myfinance.app.ui.LocalHideBalances provides vm.hideBalances,
            com.myfinance.app.ui.LocalToggleHide provides { vm.toggleHide() },
        ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == 0) "My Finance" else tabs[tab].label, style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Page),
                    actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = BrandDark, selectedTextColor = BrandDark))
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Page)) {
                when {
                    vm.snap == null && vm.error != null -> CenterText("Couldn't read your SMS: ${vm.error}")
                    vm.snap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Reading your M-Pesa and bank messages…", Modifier.padding(top = 12.dp))
                        }
                    }
                    else -> when (tab) {
                        0 -> HomeScreen(vm) { tab = it }
                        1 -> SpendingScreen(vm)
                        2 -> LoansScreen(vm)
                        3 -> BusinessScreen(vm)
                        else -> BudgetScreen(vm)
                    }
                }
                if (vm.loading && vm.snap != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.tagRequest?.let { req ->
                    val tx = vm.snap?.txs?.firstOrNull { it.id == req.id } ?: req
                    com.myfinance.app.ui.GigTagDialog(
                        tx, vm.gigNames, onDismiss = { vm.tagRequest = null },
                        onSave = { gig, always -> vm.tag(tx, gig, always) }, onNot = { vm.notGig(tx) },
                    )
                }
            }
        }
        }
    }

    @Composable
    private fun PermissionScreen(onGrant: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("My Finance", style = MaterialTheme.typography.headlineMedium)
            Text(
                "This app reads your M-Pesa, bank and loan SMS to track your spending, loans and business. " +
                    "Everything stays on this phone — nothing is uploaded anywhere.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = onGrant) { Text("Allow SMS access") }
            Text("Notifications are used for loan due-date and budget reminders.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun CenterText(t: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(t, textAlign = TextAlign.Center) }
}
