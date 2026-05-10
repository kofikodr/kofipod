// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.player.MiniPlayer
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Top-level app chrome. Picks between the phone layout (Scaffold with bottom bar + mini player)
 * and the tablet layout (Row(rail, Column(content, dockedMiniPlayer))) based on
 * [LocalTabletSize]. The phone branch is the existing AppShell Scaffold body moved
 * wholesale; behavior is unchanged for phones.
 *
 * Tablet branch currently uses placeholder rail + docked mini-player composables.
 * Tasks 1.3 and 1.4 of the tablet Phase 1 plan replace them with real components.
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
            snackbarHostState = snackbarHostState,
            onPlayerScreen = onPlayerScreen,
            size = tabletSize,
            content = content,
        )
    }
}

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

@Composable
private fun TabletScaffold(
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
                PlaceholderRail(size)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(Modifier.weight(1f)) { content() }
                if (!onPlayerScreen) {
                    PlaceholderDockedMiniPlayer()
                }
            }
        }
    }
}

/**
 * Placeholder navigation rail. Replaced by the real rail composable in Phase 1 Task 1.3.
 * Width follows the spec's icon-only baseline (72.dp) to keep the layout structure stable.
 */
@Composable
private fun PlaceholderRail(size: TabletSize) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(c.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Rail\n${size.name}", color = c.textMute)
    }
}

/**
 * Placeholder docked mini-player. Replaced by the real component in Phase 1 Task 1.4.
 */
@Composable
private fun PlaceholderDockedMiniPlayer() {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(c.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Mini-player (placeholder)", color = c.textMute)
    }
}
