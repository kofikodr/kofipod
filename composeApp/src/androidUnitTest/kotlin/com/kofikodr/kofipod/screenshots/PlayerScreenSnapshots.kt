// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.player.PlayerContent
import com.kofikodr.kofipod.ui.screens.player.PlayerUiState
import com.kofikodr.kofipod.ui.screens.player.emptyAudioLevelsForPreview
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [PlayerContent] — the stateless body of `PlayerScreen`.
 *
 * - Phone (`size == null`) is the byte-identical regression guard at 412×892 dp MDPI.
 *   The legacy phone layout MUST NOT drift across tablet branches.
 * - Tablet portrait/landscape variants exercise the artwork max-width cap, the title
 *   block extra horizontal padding, and the action-strip icon-size + label-visibility
 *   toggles wired up in Phase 7.
 *
 * The drag-to-dismiss `nestedScroll`/`offset` modifier chain on `PlayerScreen` is
 * intentionally NOT part of these snapshots — it isn't layout-bearing and would
 * pull `Animatable` / coroutine state into a snapshot harness that has no business
 * with either.
 */
class PlayerScreenSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            // Default = phone; per-test overrides hop to tablet sizes via unsafeUpdateConfig.
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun playerPlaying_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot {
            PlayerHarness(state = PLAYING_FIXTURE, size = null)
        }
    }

    @Test
    fun playerPlaying_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            PlayerHarness(state = PLAYING_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun playerPlaying_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            PlayerHarness(state = PLAYING_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun playerPlaying_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            PlayerHarness(state = PLAYING_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun playerPlaying_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            PlayerHarness(state = PLAYING_FIXTURE, size = TabletSize.Tablet10Land)
        }
    }

    private fun resetToPhoneConfig() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
        )
    }

    private fun useTabletDeviceConfig(
        width: Int,
        height: Int,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = width,
                    screenHeight = height,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
        )
    }

    private fun useLandscapeTabletConfig(
        width: Int,
        height: Int,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = width,
                    screenHeight = height,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
        )
    }
}

@Composable
private fun PlayerHarness(
    state: PlayerUiState,
    size: TabletSize?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            // Mirror the outer container PlayerScreen wraps PlayerContent in: 20dp
            // horizontal page padding + bg fill. The drag/scroll modifier chain is
            // intentionally skipped (not layout-bearing).
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(c.bg)
                        .padding(horizontal = 20.dp),
            ) {
                PlayerContent(
                    state = state,
                    entitlement = ProEntitlement.Free,
                    isProTipDismissed = false,
                    audioLevels = emptyAudioLevelsForPreview(),
                    size = size,
                    onBack = {},
                    onShare = {},
                    onGoToPodcast = {},
                    onMarkPlayed = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPrev = {},
                    onNext = {},
                    onSnipTapped = {},
                    onBookmarkTapped = {},
                    onCycleSpeed = {},
                    onSetSleep = {},
                    onDismissProTip = {},
                )
            }
        }
    }
}

private val PLAYING_FIXTURE: PlayerUiState =
    PlayerUiState(
        player =
            PlayerState(
                episodeId = "ep-214",
                podcastId = "signal-noise",
                title = "Vim, Emacs, and the first taste of extensibility",
                podcastTitle = "Signal & Noise",
                artworkUrl = "",
                episodeNumber = 214,
                isPlaying = true,
                positionMs = 18L * 60_000 + 42_000,
                durationMs = 37L * 60_000 + 26_000,
                bufferedMs = 22L * 60_000,
                speed = 1.4f,
                sleepRemainingMs = null,
                isLocalSource = false,
            ),
        hasPrev = true,
        hasNext = true,
        skipForwardSec = 30,
        skipBackSec = 10,
        toast = null,
    )
