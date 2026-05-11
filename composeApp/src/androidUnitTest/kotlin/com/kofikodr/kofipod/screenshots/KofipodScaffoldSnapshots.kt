// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.player.DockedMiniPlayerContent
import com.kofikodr.kofipod.ui.shell.RailContent
import com.kofikodr.kofipod.ui.shell.TabletScaffoldContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for the tablet [TabletScaffoldContent] integration —
 * Row(rail, Column(content placeholder, docked mini-player)) at the Tablet10Land
 * configuration (1400x1000, Expanded rail).
 *
 * Renders [TabletScaffoldContent] directly with stateless slot composables so the
 * test never touches Koin or the real [com.kofikodr.kofipod.playback.KofipodPlayer]
 * (whose Android actual constructor eagerly builds a `MediaController` in `init`
 * and crashes inside Paparazzi). The rail slot uses [RailContent] and the
 * mini-player slot uses [DockedMiniPlayerContent] with a hardcoded [PlayerState].
 */
class KofipodScaffoldSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1400,
                    screenHeight = 1000,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun tabletScaffold_10Land_withMiniPlayer_light() =
        paparazzi.snapshot {
            TabletScaffoldHarness(
                size = TabletSize.Tablet10Land,
                showRail = true,
                showDockedMiniPlayer = true,
            )
        }

    @Test
    fun tabletScaffold_10Land_playerRouteHidesChrome_light() =
        paparazzi.snapshot {
            TabletScaffoldHarness(
                size = TabletSize.Tablet10Land,
                showRail = false,
                showDockedMiniPlayer = false,
            )
        }
}

private val FAKE_PLAYER_STATE =
    PlayerState(
        episodeId = "ep-1",
        podcastId = "maker-talk",
        title = "Why we ship small",
        podcastTitle = "Maker Talk",
        artworkUrl = "",
        episodeNumber = 42,
        isPlaying = true,
        positionMs = 90_000L,
        durationMs = 1_800_000L,
        bufferedMs = 0L,
        speed = 1.0f,
        sleepRemainingMs = null,
        isLocalSource = false,
    )

@Composable
private fun TabletScaffoldHarness(
    size: TabletSize,
    showRail: Boolean,
    showDockedMiniPlayer: Boolean,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        TabletScaffoldContent(
            showRail = showRail,
            showDockedMiniPlayer = showDockedMiniPlayer,
            snackbarHostState = remember { SnackbarHostState() },
            rail = {
                RailContent(
                    currentRoute = Route.Library::class.qualifiedName,
                    mode = size.railMode,
                    onSelect = {},
                )
            },
            dockedMiniPlayer = {
                DockedMiniPlayerContent(
                    state = FAKE_PLAYER_STATE,
                    onOpen = {},
                    onPlayPause = {},
                    onDismiss = {},
                )
            },
            content = {
                Box(Modifier.fillMaxSize().background(c.surface)) {
                    Text(
                        "Content placeholder",
                        modifier = Modifier.align(Alignment.Center),
                        color = c.text,
                    )
                }
            },
        )
    }
}
