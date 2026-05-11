// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.playlists.SmartPlaylistPredicate
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.palette.PaletteCache
import com.kofikodr.kofipod.ui.palette.PalettePort
import com.kofikodr.kofipod.ui.screens.library.LibraryContent
import com.kofikodr.kofipod.ui.screens.library.LibraryGroup
import com.kofikodr.kofipod.ui.screens.library.LibraryUiState
import com.kofikodr.kofipod.ui.screens.library.SmartPlaylistTileData
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Paparazzi baselines for [LibraryContent].
 *
 * - Phone (`size == null`): empty-state at 412×892 dp MDPI. Locked as a
 *   byte-identical regression guard.
 * - Tablet portrait (`size == Tablet8Port` / `Tablet10Port`): populated + empty
 *   fixtures at 800×1200 / 1000×1400 dp MDPI matching the spec's tablet mocks.
 * - Tablet landscape (`Tablet8Land` / `Tablet10Land`): empty fixture (master pane
 *   takes full width) + populated fixture (Recently opened renders in the detail
 *   pane).
 *
 * Populated fixture deliberately uses `smartPlaylists = emptyList()` — the
 * SmartPlaylist chrome is exercised by its own snapshot suite.
 */
class LibraryScreenSnapshots {
    @After
    fun tearDownKoin() {
        // Defensive: Koin-compose's KoinApplication composable uses a scoped context
        // on current versions, but stopping the global context between snapshots
        // guarantees isolation regardless of which Koin-compose version is in use.
        runCatching { stopKoin() }
    }

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
    fun libraryFoldersRow_smartPlaylist_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            LibraryHarness(state = POPULATED_WITH_SMART_PLAYLISTS_FIXTURE, size = TabletSize.Tablet10Port)
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
    fun libraryPopulated_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            LibraryHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun libraryPopulated_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            LibraryHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Land)
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

private val NoOpPalettePort =
    object : PalettePort {
        override suspend fun extract(model: Any?): Pair<Color, Color>? = null
    }

@Composable
private fun LibraryHarness(
    state: LibraryUiState,
    size: TabletSize?,
) {
    KoinApplication(application = {
        modules(
            module {
                single<PalettePort> { NoOpPalettePort }
                single { PaletteCache(port = get()) }
            },
        )
    }) {
        KofipodTheme(KofipodThemeMode.Light) {
            val c = LocalKofipodColors.current
            Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
                LibraryContent(
                    state = state,
                    onOpenPodcast = {},
                    onOpenList = {},
                    onOpenSearch = {},
                    onOpenStarterPack = {},
                    onOpenLibrarySearch = {},
                    onOpenBookmarks = {},
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
        smartPlaylists = emptyList(),
    )
}

// Trims the populated fixture to a single regular folder so the smart-playlist cards
// land inside the 10"P viewport (~3 cards visible at 320 dp + 12 dp gap inside 960 dp
// of content). Order in the row is: regular lists → Unfiled → smart playlists, so the
// baseline shows: Morning (regular) → Unfiled → Unplayed (smart playlist).
private val POPULATED_WITH_SMART_PLAYLISTS_FIXTURE: LibraryUiState by lazy {
    val morning = list("morning", "Morning", 0)
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
        smartPlaylists =
            listOf(
                SmartPlaylistTileData(
                    playlist =
                        SmartPlaylist(
                            id = "sp1",
                            name = "Unplayed",
                            predicate = SmartPlaylistPredicate.EMPTY,
                            createdAtMs = 0L,
                        ),
                    matchedCount = 12,
                ),
                SmartPlaylistTileData(
                    playlist =
                        SmartPlaylist(
                            id = "sp2",
                            name = "Recent downloads",
                            predicate = SmartPlaylistPredicate.EMPTY,
                            createdAtMs = 0L,
                        ),
                    matchedCount = 1,
                ),
            ),
    )
}
