// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.data.repo.DownloadRow
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.downloads.DownloadsContent
import com.kofikodr.kofipod.ui.screens.downloads.DownloadsUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [DownloadsContent].
 *
 * - Phone (`size == null`): populated body at 412×892 dp MDPI. Locked here as a
 *   byte-identical regression guard; **must not drift** when tablet branches change.
 * - Tablet portrait (`Tablet8Port`): full-width layout (no max-width cap). Empty +
 *   populated baselines at 800×1200 dp MDPI.
 * - Tablet landscape (`Tablet8Land` / `Tablet10Land`) + 10" portrait (`Tablet10Port`):
 *   single column centered with `widthIn(max = 800.dp)`.
 */
class DownloadsScreenSnapshots {
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
    fun downloadsPopulated_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot {
            DownloadsHarness(state = POPULATED_FIXTURE, size = null)
        }
    }

    @Test
    fun downloadsEmpty_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            DownloadsHarness(state = DownloadsUiState(), size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun downloadsPopulated_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            DownloadsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun downloadsPopulated_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            DownloadsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun downloadsPopulated_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            DownloadsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun downloadsPopulated_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            DownloadsHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun downloadsEmpty_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            DownloadsHarness(state = DownloadsUiState(), size = TabletSize.Tablet10Land)
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
private fun DownloadsHarness(
    state: DownloadsUiState,
    size: TabletSize?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
                DownloadsContent(
                    state = state,
                    onOpenEpisode = {},
                    onCancel = {},
                    onDelete = {},
                    size = size,
                )
            }
        }
    }
}

private fun dl(
    id: String,
    state: String,
    podcastTitle: String,
    episodeTitle: String,
    downloadedBytes: Long,
    totalBytes: Long,
    completedAt: Long? = null,
    startedAt: Long? = null,
    source: String = "Manual",
): DownloadRow =
    DownloadRow(
        episodeId = id,
        state = state,
        localPath = if (state == "Completed") "/tmp/$id.mp3" else null,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        source = source,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = null,
        episodeTitle = episodeTitle,
        podcastId = "pod-$id",
        podcastTitle = podcastTitle,
        artworkUrl = null,
    )

// Exercises every row variant so any rendering regression shows up:
//   Downloading (InProgressRow) + Up next (QueuedRow) + Downloaded (CompletedRow).
private val POPULATED_FIXTURE: DownloadsUiState by lazy {
    DownloadsUiState(
        downloading =
            listOf(
                dl(
                    id = "d1",
                    state = "Downloading",
                    podcastTitle = "Signal & Noise",
                    episodeTitle = "How the world's smallest engines work",
                    downloadedBytes = 18L * 1024 * 1024,
                    totalBytes = 42L * 1024 * 1024,
                    startedAt = 1_000L,
                ),
            ),
        queued =
            listOf(
                dl(
                    id = "q1",
                    state = "Queued",
                    podcastTitle = "The Morning Brief",
                    episodeTitle = "Field guide to weak ties",
                    downloadedBytes = 0L,
                    totalBytes = 28L * 1024 * 1024,
                ),
                dl(
                    id = "q2",
                    state = "Paused",
                    podcastTitle = "Deep Reads",
                    episodeTitle = "When clocks disagree",
                    downloadedBytes = 4L * 1024 * 1024,
                    totalBytes = 36L * 1024 * 1024,
                ),
            ),
        completed =
            listOf(
                dl(
                    id = "c1",
                    state = "Completed",
                    podcastTitle = "Unboxed",
                    episodeTitle = "Soft systems, hard limits",
                    downloadedBytes = 22L * 1024 * 1024,
                    totalBytes = 22L * 1024 * 1024,
                    completedAt = 5_000L,
                ),
                dl(
                    id = "c2",
                    state = "Completed",
                    podcastTitle = "Field Notes",
                    episodeTitle = "Pilot: signal & noise",
                    downloadedBytes = 19L * 1024 * 1024,
                    totalBytes = 19L * 1024 * 1024,
                    completedAt = 6_000L,
                ),
            ),
        failed = emptyList(),
        capBytes = (2.4 * 1024 * 1024 * 1024).toLong(),
    )
}
