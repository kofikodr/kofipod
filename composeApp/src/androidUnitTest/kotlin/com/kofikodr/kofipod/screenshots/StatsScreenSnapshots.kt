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
import com.kofikodr.kofipod.data.repo.DailyListening
import com.kofikodr.kofipod.data.repo.StatsSnapshot
import com.kofikodr.kofipod.data.repo.TopPodcast
import com.kofikodr.kofipod.domain.Tier
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.stats.StatsContent
import com.kofikodr.kofipod.ui.screens.stats.StatsUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [StatsContent].
 *
 * - Phone (`size == null`): full-width body at 412×892 dp MDPI. Locked here as a
 *   byte-identical regression guard; **must not drift** when tablet branches change.
 * - 8" portrait (`Tablet8Port`): full-width layout (no max-width cap).
 * - 8" landscape, 10" portrait, 10" landscape: single column centered with
 *   `widthIn(max = 800.dp)`.
 */
class StatsScreenSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
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
    fun statsPopulated_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot {
            StatsHarness(state = POPULATED_FIXTURE, size = null)
        }
    }

    @Test
    fun statsPopulated_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            StatsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun statsPopulated_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            StatsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun statsPopulated_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            StatsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun statsPopulated_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            StatsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Land)
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
private fun StatsHarness(
    state: StatsUiState,
    size: TabletSize?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            StatsContent(
                state = state,
                onBack = {},
                onOpenPodcast = {},
                onOpenTierExplain = {},
                size = size,
            )
        }
    }
}

// A deterministic fixture exercising the full populated path:
//   TierHeroCard (Pour Over, rank 3 with a next tier) +
//   TotalPlaybackCard + DailyPlaybackCard (varied bar heights, today highlighted) +
//   Row(StreakCard, CompletedCard) +
//   Top podcasts section with two entries.
private val TODAY: Int = 20_000
private val WINDOW_FROM: Int = TODAY - 29

private val DAILY_SERIES: List<DailyListening> =
    (0 until 30).map { i ->
        val day = WINDOW_FROM + i
        // Varied non-zero seconds so the bar chart has shape; a couple of zero days
        // so the streak dots render both active + inactive states.
        val seconds: Long =
            when (i % 7) {
                0 -> 18L * 60L
                1 -> 32L * 60L
                2 -> 0L
                3 -> 25L * 60L
                4 -> 40L * 60L
                5 -> 12L * 60L
                else -> 28L * 60L
            }
        DailyListening(epochDay = day, seconds = seconds)
    }

private val POPULATED_FIXTURE: StatsUiState by lazy {
    StatsUiState(
        snapshot =
            StatsSnapshot(
                totalSecondsAllTime = (21L * 3600L) + (14L * 60L),
                totalSecondsLast7d = (3L * 3600L) + (42L * 60L),
                totalSecondsLast30d = (12L * 3600L) + (30L * 60L),
                avgMinPerDayLast30d = 26.0,
                dailySeriesLast30d = DAILY_SERIES,
                topPodcasts =
                    listOf(
                        TopPodcast(
                            podcastId = "p1",
                            podcastTitle = "Signal & Noise",
                            seconds = 4L * 3600L,
                            artworkUrl = null,
                        ),
                        TopPodcast(
                            podcastId = "p2",
                            podcastTitle = "Deep Reads",
                            seconds = 2L * 3600L,
                            artworkUrl = null,
                        ),
                    ),
                completedAllTime = 34L,
                completedLast30d = 12L,
                currentStreak = 14,
                longestStreak = 21,
                daysSinceFirstListen = 90,
                today = TODAY,
                windowFromDay = WINDOW_FROM,
                tier = Tier.PourOver,
                tierBeforeThisCompute = Tier.PourOver,
            ),
        hasAnyData = true,
    )
}
