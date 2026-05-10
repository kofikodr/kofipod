// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.player.DockedMiniPlayer
import com.kofikodr.kofipod.ui.player.MiniPlayer
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * App-level scaffold that renders either the phone bottom-bar layout or the tablet
 * rail-plus-docked-mini-player layout based on [LocalTabletSize].
 *
 * Phone path is selected when `LocalTabletSize.current == null`. To activate the
 * tablet path on real devices, wrap the app shell in `WithTabletSize` (done in
 * Task 1.6 of `docs/superpowers/plans/2026-05-11-tablet-phase-01-foundation.md`).
 * Without that wrapper the CompositionLocal default is null and the phone path
 * is always chosen — by design for this task; the tablet branch is exercised
 * via Paparazzi snapshots that inject [LocalTabletSize] directly.
 *
 * Tablet branch wires [com.kofikodr.kofipod.ui.shell.KofipodNavigationRail] beside
 * a content column whose foot carries the docked [DockedMiniPlayer] (spec §4).
 */
@Composable
fun KofipodScaffold(
    nav: NavHostController,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    val tabletSize = LocalTabletSize.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onPlayerScreen = currentRoute == Route.Player::class.qualifiedName

    if (tabletSize == null) {
        PhoneScaffold(
            nav = nav,
            snackbarHostState = snackbarHostState,
            onPlayerScreen = onPlayerScreen,
            content = content,
        )
    } else {
        TabletScaffold(
            nav = nav,
            snackbarHostState = snackbarHostState,
            onPlayerScreen = onPlayerScreen,
            size = tabletSize,
            content = content,
        )
    }
}

/** Phone branch: Material Scaffold with bottom mini-player + bottom navigation bar. */
@Composable
private fun PhoneScaffold(
    nav: NavHostController,
    snackbarHostState: SnackbarHostState,
    onPlayerScreen: Boolean,
    content: @Composable () -> Unit,
) {
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
            content()
        }
    }
}

// TODO(tablet-scaffold-snapshot): Phase 1 Task 1.2 originally included a
// Paparazzi smoke test of the full Row(rail, Column(content, mini-player))
// scaffold at 1400x1000. Task 1.4 replaced the placeholder mini-player with
// the real DockedMiniPlayer which pulls KofipodPlayer from Koin; that
// constructor builds a MediaController eagerly in init and crashes inside
// Paparazzi. Restoring the integration snapshot requires either a
// KofipodPlayer Koin test module or a stateless KofipodScaffoldContent
// seam that takes PlayerState as a parameter. Track for Phase 1 polish
// before later phases compound the rotation-coverage gap.

/** Tablet branch: leading navigation rail beside a column of content + docked mini-player. */
@Composable
private fun TabletScaffold(
    nav: NavHostController,
    snackbarHostState: SnackbarHostState,
    onPlayerScreen: Boolean,
    size: TabletSize,
    content: @Composable () -> Unit,
) {
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
    ) { padding ->
        Row(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (!onPlayerScreen) {
                KofipodNavigationRail(nav = nav, size = size)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(Modifier.weight(1f)) { content() }
                if (!onPlayerScreen) {
                    DockedMiniPlayer(
                        onOpen = {
                            nav.navigate(
                                Route.Player,
                                navOptions { launchSingleTop = true },
                            )
                        },
                    )
                }
            }
        }
    }
}
