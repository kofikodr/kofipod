// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.draw.alpha
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
                // MiniPlayer visibility is animated with an asymmetric enter/exit:
                // - Entering Player: hide immediately (exit duration 0) so the
                //   MiniPlayer never co-exists on screen with the expanded Player
                //   during nav's pop/push transition.
                // - Leaving Player: fade in over the Player's exit animation so
                //   the chrome flip isn't a visible single-frame flash.
                // Replaces a hard `if (!onPlayerScreen)` gate, which let
                // currentBackStack flip to false at the start of the pop while
                // PlayerScreen was still rendering its exit transition.
                AnimatedVisibility(
                    visible = !onPlayerScreen,
                    enter = fadeIn(tween(MINIPLAYER_FADE_IN_MS)),
                    exit = ExitTransition.None,
                ) {
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

/** Tablet branch: leading navigation rail beside a column of content + docked mini-player. */
@Composable
private fun TabletScaffold(
    nav: NavHostController,
    snackbarHostState: SnackbarHostState,
    onPlayerScreen: Boolean,
    size: TabletSize,
    content: @Composable () -> Unit,
) {
    TabletScaffoldContent(
        showRail = !onPlayerScreen,
        showDockedMiniPlayer = !onPlayerScreen,
        snackbarHostState = snackbarHostState,
        rail = { KofipodNavigationRail(nav = nav, size = size) },
        dockedMiniPlayer = {
            DockedMiniPlayer(
                onOpen = {
                    nav.navigate(
                        Route.Player,
                        navOptions { launchSingleTop = true },
                    )
                },
            )
        },
        content = content,
    )
}

/**
 * Stateless body of the tablet scaffold — public for snapshot tests.
 *
 * Caller provides [rail] and [dockedMiniPlayer] as composable slots; [showRail] and
 * [showDockedMiniPlayer] gate whether each slot is rendered (the production wrapper
 * uses these to suppress chrome on the [Route.Player] screen). Factoring this layer
 * out of [TabletScaffold] lets Paparazzi test the Row(rail, Column(content, mini))
 * geometry without touching Koin or the real [com.kofikodr.kofipod.playback.KofipodPlayer]
 * (whose Android actual eagerly builds a `MediaController` in `init`, which crashes
 * inside Paparazzi).
 */
@Composable
internal fun TabletScaffoldContent(
    showRail: Boolean,
    showDockedMiniPlayer: Boolean,
    snackbarHostState: SnackbarHostState,
    rail: @Composable () -> Unit,
    dockedMiniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    val railAlpha by animateFloatAsState(
        targetValue = if (showRail) 1f else 0f,
        animationSpec = if (showRail) tween(MINIPLAYER_FADE_IN_MS) else snap(),
        label = "Tablet rail alpha",
    )
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
            if (showRail || railAlpha > 0f) {
                Box(Modifier.alpha(railAlpha)) {
                    rail()
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(Modifier.weight(1f)) { content() }
                // Same asymmetric fade as PhoneScaffold's MiniPlayer — mask the
                // single-frame overlap when the Player route pops/pushes.
                AnimatedVisibility(
                    visible = showDockedMiniPlayer,
                    enter = fadeIn(tween(MINIPLAYER_FADE_IN_MS)),
                    exit = ExitTransition.None,
                ) {
                    dockedMiniPlayer()
                }
            }
        }
    }
}

private const val MINIPLAYER_FADE_IN_MS = 220
