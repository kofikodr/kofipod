// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import app.kofipod.backup.BackupController
import app.kofipod.backup.BackupPickerHost
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.opml.OpmlPickerHost
import app.kofipod.ui.UiEvent
import app.kofipod.ui.UiEventBus
import app.kofipod.ui.nav.DeepLinks
import app.kofipod.ui.nav.KofipodNavHost
import app.kofipod.ui.nav.Route
import app.kofipod.ui.player.MiniPlayer
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val bus: UiEventBus = koinInject()
    val backupController: BackupController = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    // First-composition pass: if the previous process exited via a restore confirm,
    // PendingRestore.consumeIfPresent already replaced the DB; surface a snackbar so
    // the user knows their library was restored. Idempotent — flag clears on read.
    LaunchedEffect(backupController) {
        backupController.notifyRestoreCompletedIfPending()
    }
    LaunchedEffect(bus) {
        bus.events.collect { event ->
            when (event) {
                is UiEvent.Snackbar -> {
                    // Replace any in-flight snackbar so back-to-back failures don't queue.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = event.message)
                }
            }
        }
    }
    LaunchedEffect(nav) {
        DeepLinks.openPlayer.collect {
            if (nav.currentDestination?.route != Route.Player::class.qualifiedName) {
                nav.navigate(
                    Route.Player,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                    },
                )
            }
        }
    }
    val onPlayerScreen = currentRoute == Route.Player::class.qualifiedName
    val c = LocalKofipodColors.current
    Scaffold(
        containerColor = c.bg,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = c.surface,
                    contentColor = c.text,
                )
            }
        },
        bottomBar = {
            Column {
                if (!onPlayerScreen) {
                    MiniPlayer(
                        onOpen = {
                            nav.navigate(
                                Route.Player,
                                navOptions { launchSingleTop = true },
                            )
                        },
                    )
                }
                BottomNav(nav)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            KofipodNavHost(nav)
        }
    }
    // Hoisted at the shell level so SAF launchers stay rooted regardless of which
    // screen triggered the import/export or backup pick. No-ops on iOS.
    OpmlPickerHost()
    BackupPickerHost()

    // First-launch disclosure: gates all diagnostic sends until the user
    // taps "Got it" or "Open Settings". `initial = true` avoids a flash of
    // the sheet on every launch — the first real emission either confirms
    // true (sheet stays hidden) or flips to false (sheet appears).
    val diagnostics: DiagnosticsConfigRepository = koinInject()
    val acknowledged by diagnostics.disclosureAcknowledged.collectAsState(initial = true)
    val ackScope = rememberCoroutineScope()
    DiagnosticsDisclosureSheet(
        visible = !acknowledged,
        onAcknowledge = { ackScope.launch { diagnostics.acknowledgeDisclosure() } },
        onOpenSettings = {
            if (nav.currentDestination?.route != Route.Settings::class.qualifiedName) {
                nav.navigate(
                    Route.Settings,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                    },
                )
            }
        },
    )
}

private data class Tab(
    val route: Route,
    val routeKey: String,
    val label: String,
    val icon: KPIconName,
)

private val TABS =
    listOf(
        Tab(Route.Library, Route.Library::class.qualifiedName!!, "Library", KPIconName.Library),
        Tab(Route.Search, Route.Search::class.qualifiedName!!, "Search", KPIconName.Search),
        Tab(Route.Downloads, Route.Downloads::class.qualifiedName!!, "Downloads", KPIconName.Downloads),
        Tab(Route.Settings, Route.Settings::class.qualifiedName!!, "Settings", KPIconName.Settings),
    )

@Composable
private fun BottomNav(nav: NavHostController) {
    val c = LocalKofipodColors.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(width = 0.5.dp, color = c.border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TABS.forEach { tab ->
                val selected = currentRoute == tab.routeKey
                TabItem(
                    tab = tab,
                    selected = selected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selected) return@TabItem
                        nav.popBackStack()
                        nav.navigate(tab.route, navOptions { launchSingleTop = true })
                    },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val bg = if (selected) c.purpleTint else androidx.compose.ui.graphics.Color.Transparent
    val iconColor = if (selected) c.purple else c.textMute
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable { onClick() }
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KPIcon(
            name = tab.icon,
            color = iconColor,
            size = 22.dp,
            strokeWidth = if (selected) 2.2f else 1.8f,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            color = if (selected) c.purple else c.textMute,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 10.5.sp,
        )
    }
}
