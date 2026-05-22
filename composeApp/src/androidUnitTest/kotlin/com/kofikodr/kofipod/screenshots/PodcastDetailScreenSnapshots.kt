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
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.detail.ActivePlayback
import com.kofikodr.kofipod.ui.screens.detail.DetailUiState
import com.kofikodr.kofipod.ui.screens.detail.PodcastDetailContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [PodcastDetailContent].
 *
 * - Phone (`size == null`): regression check at 412×892 dp MDPI. Must remain
 *   byte-identical across Phase 8 — the phone path branches by `size == null` and
 *   reuses today's single-column body verbatim.
 * - Tablet portraits (`Tablet8Port` / `Tablet10Port`): single-column body (same as
 *   phone shape, wider canvas).
 * - Tablet landscapes (`Tablet8Land` / `Tablet10Land`): master-detail. Master mirrors
 *   the portrait body; detail pane previews the selected episode. The `noSelection`
 *   variant covers the empty-detail hint path by passing `selectedEpisode = null`
 *   directly to `PodcastDetailContent` (the screen-level default-selection logic
 *   that picks the first episode only runs in the stateful wrapper, not here).
 */
class PodcastDetailScreenSnapshots {
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
    fun podcastDetail_populated_phone_light() =
        paparazzi.snapshot {
            PodcastDetailHarness(
                state = POPULATED_FIXTURE,
                size = null,
                selectedEpisode = null,
            )
        }

    @Test
    fun podcastDetail_populated_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            PodcastDetailHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet8Port,
                selectedEpisode = null,
            )
        }
    }

    @Test
    fun podcastDetail_populated_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            PodcastDetailHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet10Port,
                selectedEpisode = null,
            )
        }
    }

    // Landscape `withSelection` baselines were retired in the slice that wired the
    // right pane to the full `EpisodeDetailScreen` (Koin-backed + stateful) — that
    // composable can't be driven from Paparazzi without standing up Koin and the
    // playback / repository graph. Coverage of the embedded body lives in
    // `EpisodeDetailScreenSnapshots` (which drives the stateless `EpisodeDetailContent`
    // directly in `HostMode.MasterDetailPane`). What's still snap-checked here is the
    // landscape master pane + empty-detail geometry via the `noSelection` cases.
    @Test
    fun podcastDetail_populated_tablet8Land_noSelection_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            PodcastDetailHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet8Land,
                selectedEpisode = null,
            )
        }
    }

    @Test
    fun podcastDetail_populated_tablet10Land_noSelection_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            PodcastDetailHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet10Land,
                selectedEpisode = null,
            )
        }
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
private fun PodcastDetailHarness(
    state: DetailUiState,
    size: TabletSize?,
    selectedEpisode: Episode?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            PodcastDetailContent(
                state = state,
                playingEpisodeId = null,
                activePlaybackFlow = MutableStateFlow(ActivePlayback()),
                downloadStatesFlow = MutableStateFlow(emptyMap()),
                refreshing = false,
                selectedEpisode = selectedEpisode,
                size = size,
                onBack = {},
                onSharePodcast = {},
                onRefresh = {},
                onSaveTap = {},
                onToggleBell = {},
                onDownloadNewest = {},
                onCancelNewestDownload = {},
                onDeleteNewestDownload = {},
                onToggleAutoDownload = {},
                onEpisodeTap = {},
                onPlayEpisode = {},
                onDownloadEpisode = {},
                onCancelDownload = {},
                onDeleteEpisodeDownload = {},
                onShareEpisode = {},
                onLoadMore = {},
            )
        }
    }
}

private fun episode(
    id: String,
    seq: Int,
    title: String,
    durationSec: Long,
    publishedAt: Long,
    description: String = "",
): Episode =
    Episode(
        id = id,
        podcastId = "p1",
        guid = id,
        title = title,
        description = description,
        publishedAt = publishedAt,
        durationSec = durationSec,
        enclosureUrl = "https://example.test/$id.mp3",
        enclosureMimeType = "audio/mpeg",
        fileSizeBytes = 24L * 1024 * 1024,
        seasonNumber = null,
        episodeNumber = seq.toLong(),
        imageUrl = "",
        chaptersUrl = null,
        transcriptUrl = null,
    )

private val SUMMARY: PodcastSummary =
    PodcastSummary(
        id = "1001",
        feedId = 1001L,
        title = "Signal & Noise",
        author = "Maggie Pereira",
        description =
            "Weekly conversations on attention, systems, and the quiet edges of " +
                "modern work. Half interview, half field guide.",
        artworkUrl = "",
        feedUrl = "https://example.test/signal-noise.xml",
        category = "Technology",
        episodeCount = 214,
    )

private val EPISODES: List<Episode> by lazy {
    listOf(
        episode(
            id = "e214",
            seq = 214,
            title = "How the world's smallest engines work",
            durationSec = 42L * 60,
            publishedAt = 1_715_000_000_000L,
            description =
                "Down in the cytoplasm there's a population of motors that have been " +
                    "spinning at thousands of RPM for a billion years. We trace what we " +
                    "know about them and how the field figured it out.",
        ),
        episode(
            id = "e213",
            seq = 213,
            title = "Field guide to weak ties",
            durationSec = 36L * 60,
            publishedAt = 1_714_400_000_000L,
        ),
        episode(
            id = "e212",
            seq = 212,
            title = "When clocks disagree",
            durationSec = 51L * 60,
            publishedAt = 1_713_800_000_000L,
        ),
        episode(
            id = "e211",
            seq = 211,
            title = "Soft systems, hard limits",
            durationSec = 28L * 60,
            publishedAt = 1_713_200_000_000L,
        ),
        episode(
            id = "e210",
            seq = 210,
            title = "Pilot: signal & noise",
            durationSec = 19L * 60,
            publishedAt = 1_712_600_000_000L,
        ),
    )
}

private val POPULATED_FIXTURE: DetailUiState by lazy {
    DetailUiState(
        summary = SUMMARY,
        inLibrary = true,
        listId = null,
        autoDownload = false,
        notifyNewEpisodes = true,
        storedEpisodes = EPISODES,
        remoteEpisodes = emptyList(),
        lists = emptyList(),
        loading = false,
        loadingMore = false,
        episodeDisplayLimit = 50,
        remoteHasMore = false,
        error = null,
    )
}
