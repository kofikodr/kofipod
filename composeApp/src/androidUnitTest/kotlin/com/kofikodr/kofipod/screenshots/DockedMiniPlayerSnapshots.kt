// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.ui.player.DockedMiniPlayerContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [com.kofikodr.kofipod.ui.player.DockedMiniPlayer] — one snapshot
 * per [TabletSize] at the tablet's *content column* width (total tablet width minus the
 * rail width for that size). Renders [DockedMiniPlayerContent] with a hardcoded
 * [PlayerState] so the test never reaches Koin or the real player.
 *
 * Content column widths (per Phase 1 plan §1.4):
 * - Tablet8Port (800 dp) − IconOnly rail (72 dp) = 728 dp
 * - Tablet8Land (1200 dp) − IconOnly rail (72 dp) = 1128 dp
 * - Tablet10Port (1000 dp) − IconLabel rail (200 dp) = 800 dp
 * - Tablet10Land (1400 dp) − Expanded rail (240 dp) = 1160 dp
 */
class DockedMiniPlayerSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            // Default; per-test override via paparazzi.unsafeUpdateConfig.
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 728,
                    screenHeight = 72,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun tablet8Port_light() = snap(width = 728)

    @Test
    fun tablet8Land_light() = snap(width = 1128)

    @Test
    fun tablet10Port_light() = snap(width = 800)

    @Test
    fun tablet10Land_light() = snap(width = 1160)

    private fun snap(width: Int) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = width,
                    screenHeight = 72,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
        )
        paparazzi.snapshot {
            DockedMiniPlayerHarness()
        }
    }
}

private val STUB_STATE =
    PlayerState(
        episodeId = "ep-214",
        podcastId = "signal-noise",
        title = "A short history of developer tooling",
        podcastTitle = "Signal & Noise",
        artworkUrl = "",
        episodeNumber = 214,
        isPlaying = true,
        positionMs = (18L * 60L + 42L) * 1000L,
        durationMs = (56L * 60L + 8L) * 1000L,
        bufferedMs = 0L,
        speed = 1.4f,
        sleepRemainingMs = null,
        isLocalSource = false,
    )

@Composable
private fun DockedMiniPlayerHarness() {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            DockedMiniPlayerContent(
                state = STUB_STATE,
                onOpen = {},
                onPlayPause = {},
            )
        }
    }
}
