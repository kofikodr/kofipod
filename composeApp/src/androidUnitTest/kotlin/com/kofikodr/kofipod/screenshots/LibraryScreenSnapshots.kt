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
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.library.LibraryContent
import com.kofikodr.kofipod.ui.screens.library.LibraryGroup
import com.kofikodr.kofipod.ui.screens.library.LibraryUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [LibraryContent].
 *
 * - Phone (`size == null`): empty-state at 412×892 dp MDPI. Locked in Task 2.1 as a
 *   byte-identical regression guard; **must not drift** when adding tablet branches.
 * - Tablet portrait (`size == Tablet8Port` / `Tablet10Port`): populated + empty
 *   fixtures at 800×1200 / 1000×1400 dp MDPI matching the spec's tablet mocks.
 *
 * Populated fixture deliberately uses `smartPlaylists = emptyList()` — the
 * `SmartPlaylistTile` chrome is exercised by its own snapshot suite, and the
 * Library tablet layout doesn't change shape based on whether playlists exist.
 */
class LibraryScreenSnapshots {
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
    fun libraryEmptyState_phone_light() =
        paparazzi.snapshot {
            LibraryHarness(state = LibraryUiState(), size = null)
        }

    @Test
    fun libraryPopulated_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            LibraryHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun libraryPopulated_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            LibraryHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun libraryEmpty_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            LibraryHarness(state = LibraryUiState(), size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun libraryEmpty_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            LibraryHarness(state = LibraryUiState(), size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun libraryEmpty_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            LibraryHarness(state = LibraryUiState(), size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun libraryPopulated_tablet10Land_noSelection_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            LibraryHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet10Land,
            )
        }
    }

    @Test
    fun libraryPopulated_tablet10Land_withSelection_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            LibraryHarness(
                state = POPULATED_FIXTURE,
                size = TabletSize.Tablet10Land,
                selectedPodcastId = "p1",
                selectedEpisodes = PREVIEW_EPISODES_FIXTURE,
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

    // Landscape configs need an explicit ScreenOrientation override; without it
    // Paparazzi rotates the canvas to portrait and width/height arguments swap.
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
private fun LibraryHarness(
    state: LibraryUiState,
    size: TabletSize?,
    selectedPodcastId: String? = null,
    selectedEpisodes: List<Episode> = emptyList(),
) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            LibraryContent(
                state = state,
                selectedPodcastId = selectedPodcastId,
                selectedEpisodes = selectedEpisodes,
                onSelectPodcast = {},
                onOpenPodcast = {},
                onOpenList = {},
                onOpenSearch = {},
                onOpenStarterPack = {},
                onOpenBookmarks = {},
                onOpenStats = {},
                onOpenLibrarySearch = {},
                onOpenSmartPlaylistDetail = {},
                onNewList = {},
                onLongPressPodcast = {},
                onLongPressList = {},
                onLongPressSmartPlaylist = {},
                onImportOpml = {},
                size = size,
            )
        }
    }
}

private fun pod(
    id: String,
    title: String,
    author: String,
    listId: String?,
    addedAt: Long,
): Podcast =
    Podcast(
        id = id,
        title = title,
        author = author,
        description = "",
        artworkUrl = "",
        feedUrl = "https://example.test/$id.xml",
        listId = listId,
        autoDownloadEnabled = 0,
        notifyNewEpisodesEnabled = 1,
        lastCheckedAt = null,
        addedAt = addedAt,
        primaryCategory = "",
    )

private fun list(
    id: String,
    name: String,
    position: Long,
): PodcastList =
    PodcastList(
        id = id,
        name = name,
        position = position,
        createdAt = 0L,
    )

private fun previewEpisode(
    podcastId: String,
    seq: Int,
    title: String,
    durationSec: Long,
): Episode =
    Episode(
        id = "$podcastId-ep$seq",
        podcastId = podcastId,
        guid = "$podcastId-ep$seq",
        title = title,
        description = "",
        publishedAt = 0L,
        durationSec = durationSec,
        enclosureUrl = "",
        enclosureMimeType = "audio/mpeg",
        fileSizeBytes = 0L,
        seasonNumber = null,
        episodeNumber = seq.toLong(),
        imageUrl = "",
        chaptersUrl = null,
        transcriptUrl = null,
    )

private val PREVIEW_EPISODES_FIXTURE: List<Episode> by lazy {
    listOf(
        previewEpisode("p1", 5, "How the world's smallest engines work", 42L * 60),
        previewEpisode("p1", 4, "Field guide to weak ties", 36L * 60),
        previewEpisode("p1", 3, "When clocks disagree", 51L * 60),
        previewEpisode("p1", 2, "Soft systems, hard limits", 28L * 60),
        previewEpisode("p1", 1, "Pilot: signal & noise", 19L * 60),
    )
}

private val POPULATED_FIXTURE: LibraryUiState by lazy {
    val morning = list("morning", "Morning", 0)
    val longform = list("longform", "Long form", 1)
    val groups =
        listOf(
            LibraryGroup(
                list = morning,
                podcasts =
                    listOf(
                        pod("p1", "Signal & Noise", "Eve Hartwell", "morning", 1_000L),
                        pod("p2", "The Morning Brief", "Daniel Ortiz", "morning", 2_000L),
                    ),
            ),
            LibraryGroup(
                list = longform,
                podcasts =
                    listOf(
                        pod("p3", "Deep Reads", "Maya Chen", "longform", 1_500L),
                    ),
            ),
            LibraryGroup(
                list = null,
                podcasts =
                    listOf(
                        pod("p4", "Unboxed", "Olu Adeyemi", null, 3_000L),
                        pod("p5", "Field Notes", "Iris Park", null, 2_500L),
                    ),
            ),
        )
    LibraryUiState(
        groups = groups,
        groupsWithNew = setOf("morning"),
        statsHasUnseenTierChange = false,
        smartPlaylists = emptyList(),
    )
}
