// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Snackbar
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
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupPickerHost
import com.kofikodr.kofipod.diagnostics.DiagnosticsCapabilities
import com.kofikodr.kofipod.diagnostics.DiagnosticsConfigRepository
import com.kofikodr.kofipod.opml.OpmlPickerHost
import com.kofikodr.kofipod.pkm.PkmExportCoordinator
import com.kofikodr.kofipod.pkm.PkmExportResult
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.PaywallState
import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import com.kofikodr.kofipod.ui.layout.WithTabletSize
import com.kofikodr.kofipod.ui.nav.DeepLinks
import com.kofikodr.kofipod.ui.nav.KofipodNavHost
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.screens.bookmarks.BookmarkComposerSheet
import com.kofikodr.kofipod.ui.screens.export.ExportActionSheet
import com.kofikodr.kofipod.ui.screens.paywall.PaywallSheet
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val bus: UiEventBus = koinInject()
    val paywallRouter: PaywallRouter = koinInject()
    val paywall by paywallRouter.state.collectAsState()
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
    // PKM (Pro) export results. Copied → confirmation snackbar; Failed → error
    // snackbar with the underlying message; Shared deliberately stays silent —
    // the system share sheet is its own UI signal and a snackbar would just
    // double up on confirmation.
    val pkmCoordinator: PkmExportCoordinator = koinInject()
    LaunchedEffect(pkmCoordinator) {
        pkmCoordinator.results.collect { result ->
            when (result) {
                PkmExportResult.Copied -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = "Copied to clipboard")
                }
                PkmExportResult.Shared -> {
                    // No snackbar needed — the system share sheet is its own UI signal.
                }
                PkmExportResult.Exported -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = "Exported successfully")
                }
                is PkmExportResult.Failed -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = "Export failed: ${result.message}")
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
    LaunchedEffect(nav) {
        DeepLinks.openEpisode.collect { episodeId ->
            nav.navigate(
                Route.EpisodeDetail(episodeId),
                navOptions { launchSingleTop = true },
            )
        }
    }
    LaunchedEffect(nav) {
        DeepLinks.openLibrary.collect {
            nav.navigate(
                Route.Library,
                navOptions { launchSingleTop = true },
            )
        }
    }
    LaunchedEffect(nav) {
        DeepLinks.openSettings.collect {
            if (nav.currentDestination?.route != Route.Settings::class.qualifiedName) {
                // Pop the current top (e.g. Player) before switching tab so the bottom
                // nav state lines up with the user's mental model.
                nav.popBackStack(Route.Player::class.qualifiedName!!, inclusive = true)
                nav.navigate(
                    Route.Settings,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                    },
                )
            }
        }
    }
    WithTabletSize {
        KofipodScaffold(nav = nav, snackbarHostState = snackbarHostState) {
            KofipodNavHost(nav)
        }
    }
    // Hoisted at the shell level so SAF launchers stay rooted regardless of which
    // screen triggered the import/export or backup pick. No-ops on iOS.
    OpmlPickerHost()
    BackupPickerHost()
    // Bookmark quick-add sheet for the Pro feature. Self-gates on its own
    // BookmarkComposerState — when Hidden, returns before composing.
    BookmarkComposerSheet()
    // PKM export sink picker for the Pro feature. Self-gates on
    // PkmExportCoordinator.pendingRequest — when null, returns before composing.
    ExportActionSheet(
        coordinator = koinInject(),
        connections = koinInject(),
        onNavigateToConnections = { nav.navigate(Route.Connections) },
    )
    // Paywall lives at the shell level — a NavHost destination would render full-screen
    // and leave a blank background behind the ModalBottomSheet. Hoisting here overlays
    // the sheet on top of whichever screen triggered it.
    val visible = paywall as? PaywallState.Visible
    if (visible != null) {
        PaywallSheet(
            triggerKey = visible.triggerKey,
            onDismiss = { paywallRouter.dismiss() },
        )
    }

    // First-launch disclosure: gates all diagnostic sends until the user
    // taps "Got it" or "Open Settings". `initial = true` is load-bearing —
    // it avoids a one-frame flash of the sheet on every launch before the
    // first flow emission arrives, AND it keeps the no-capability fork
    // path quiet (the sheet's own guard short-circuits when neither
    // channel is available, so we never want a transient `false` to slip
    // through and render an empty sheet for a frame).
    val diagnostics: DiagnosticsConfigRepository = koinInject()
    val acknowledged by diagnostics.disclosureAcknowledged.collectAsState(initial = true)
    val ackScope = rememberCoroutineScope()
    // Forks / F-Droid builds with no diagnostic SDK keys configured: the
    // sheet would have nothing to disclose. Silently mark it acknowledged
    // so any flow that awaits acknowledgement (none today, but cheap
    // insurance) doesn't suspend forever. Keyed on `acknowledged` rather
    // than Unit so the no-op call only happens on the (single) emission
    // that flips it false; subsequent recompositions don't re-fire.
    LaunchedEffect(acknowledged) {
        if (!acknowledged && !DiagnosticsCapabilities.anyAvailable) {
            diagnostics.acknowledgeDisclosure()
        }
    }
    DiagnosticsDisclosureSheet(
        visible = !acknowledged,
        crashAvailable = DiagnosticsCapabilities.crashReportingAvailable,
        usageAvailable = DiagnosticsCapabilities.usageTelemetryAvailable,
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
internal fun BottomNav(nav: NavHostController) {
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
