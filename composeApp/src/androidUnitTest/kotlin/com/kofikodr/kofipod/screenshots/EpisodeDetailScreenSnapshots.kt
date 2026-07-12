// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.EpisodeChapter
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailContent
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailUiState
import com.kofikodr.kofipod.ui.screens.detail.HostMode
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Phase 9 — tablet-aware visual baselines for `EpisodeDetailContent`.
 *
 * Covers:
 *  - Phone (`size == null`) regression at 412×892 dp — must not drift across the
 *    tablet branches added in Phase 9.
 *  - 8" portrait / 8" landscape — full width, no cap.
 *  - 10" portrait — 760 dp content cap, centered.
 *  - 10" landscape — 880 dp content cap, centered.
 *  - 10" landscape on the Chapters tab — exercises the tab body inside the cap.
 *  - 10" landscape in `MasterDetailPane` host mode rendered inside a 754 dp pane,
 *    verifying no double-wrap (no TopBar, no width cap, full available width).
 *
 * The existing phone-only [EpisodeDetailSnapshots] suite stays as-is — it covers
 * the structural permutations (downloaded / no chapters / played) at phone width.
 * This suite is layout-focused and uses one rich fixture (chapters + summary on)
 * across all tablet variants.
 */
class EpisodeDetailScreenSnapshots {
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
    fun episodeDetail_overview_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot {
            DetailHarness(state = OVERVIEW_FIXTURE, size = null)
        }
    }

    @Test
    fun episodeDetail_overview_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            DetailHarness(state = OVERVIEW_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun episodeDetail_overview_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            DetailHarness(state = OVERVIEW_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun episodeDetail_overview_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            DetailHarness(state = OVERVIEW_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun episodeDetail_overview_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            DetailHarness(state = OVERVIEW_FIXTURE, size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun episodeDetail_chapters_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            DetailHarness(state = CHAPTERS_FIXTURE, size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun episodeDetail_chapters_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            DetailHarness(state = CHAPTERS_FIXTURE, size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun episodeDetail_chapters_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            DetailHarness(state = CHAPTERS_FIXTURE, size = TabletSize.Tablet10Port)
        }
    }

    @Test
    fun episodeDetail_chapters_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            // Chapters fixture has chapters but `summaryEnabled = false`, so the
            // visible tab list is just [Chapters]. rememberSaveable defaults to
            // Chapters (since Summary isn't visible) — that keeps the snapshot
            // stateless while still rendering the Chapters tab body under the
            // 880 dp cap at 10" landscape.
            DetailHarness(state = CHAPTERS_FIXTURE, size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun episodeDetail_masterDetailPane_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Light) {
                CompositionLocalProvider(LocalTabletSize provides TabletSize.Tablet10Land) {
                    val c = LocalKofipodColors.current
                    // Render inside a 754 dp pane (the right-pane width in the Phase 8
                    // master-detail split at 10" landscape, modulo divider) to verify
                    // no double-wrap chrome: no TopBar, no centered cap, content fills
                    // the available pane width.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(c.bg),
                    ) {
                        Box(
                            Modifier
                                .width(MASTER_DETAIL_PANE_WIDTH)
                                .fillMaxHeight()
                                .background(c.bg),
                        ) {
                            EpisodeDetailContent(
                                state = CHAPTERS_FIXTURE,
                                onBack = {},
                                onShare = {},
                                onPlay = {},
                                onMarkPlayed = {},
                                onDeleteDownload = {},
                                onDownload = {},
                                onChapterTap = {},
                                onOpenAiSetup = {},
                                size = TabletSize.Tablet10Land,
                                hostMode = HostMode.MasterDetailPane,
                            )
                        }
                    }
                }
            }
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
private fun DetailHarness(
    state: EpisodeDetailUiState,
    size: TabletSize?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.bg),
            ) {
                EpisodeDetailContent(
                    state = state,
                    onBack = {},
                    onShare = {},
                    onPlay = {},
                    onMarkPlayed = {},
                    onDeleteDownload = {},
                    onDownload = {},
                    onChapterTap = {},
                    onOpenAiSetup = {},
                    size = size,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fixture — one rich state used across the layout variants. Mirrors the shape
// of fixtures in EpisodeDetailSnapshots so the two suites stay readable side
// by side. Chapters list is non-trivial so the Chapters tab snapshot has rows
// to show.
// ---------------------------------------------------------------------------

private val SAMPLE_PODCAST: Podcast =
    Podcast(
        id = "pod-1",
        title = "Signal & Noise",
        author = "Engineering Studio",
        description = "",
        artworkUrl = "",
        feedUrl = "https://feed.example/sn.xml",
        listId = null,
        autoDownloadEnabled = 0L,
        notifyNewEpisodesEnabled = 1L,
        lastCheckedAt = null,
        addedAt = 0L,
        primaryCategory = "TECHNOLOGY",
        lastSeenAt = null,
    )

private val SAMPLE_EPISODE: Episode =
    Episode(
        id = "ep-214",
        podcastId = "pod-1",
        guid = "ep-214-guid",
        title = "A short history of developer tooling",
        description =
            "<p>This week we trace the long arc of developer tooling — from the " +
                "first hand-rolled assemblers through the era of mega-IDEs and back " +
                "out to today's tiny, composable terminal-native stacks. Notes at " +
                "<a href=\"https://kofipod.app/notes/214\">the show page</a>.</p>",
        publishedAt = SAMPLE_PUBLISHED_AT_MS,
        durationSec = SAMPLE_DURATION_SEC,
        enclosureUrl = "https://audio.example/ep-214.mp3",
        enclosureMimeType = "audio/mpeg",
        fileSizeBytes = SAMPLE_FILE_SIZE,
        seasonNumber = 4L,
        episodeNumber = 214L,
        imageUrl = "",
        chaptersUrl = null,
        transcriptUrl = null,
    )

private val SAMPLE_CHAPTERS: List<EpisodeChapter> =
    listOf(
        EpisodeChapter(episodeId = "ep-214", seq = 0L, startMs = 0L, title = "Cold open", imageUrl = "", linkUrl = ""),
        EpisodeChapter(
            episodeId = "ep-214",
            seq = 1L,
            startMs = CHAPTER_INTRO_MS,
            title = "From assemblers to IDEs",
            imageUrl = "",
            linkUrl = "",
        ),
        EpisodeChapter(
            episodeId = "ep-214",
            seq = 2L,
            startMs = CHAPTER_BODY_MS,
            title = "The composable-terminal renaissance",
            imageUrl = "",
            linkUrl = "",
        ),
        EpisodeChapter(
            episodeId = "ep-214",
            seq = 3L,
            startMs = CHAPTER_OUTRO_MS,
            title = "Outro and notes",
            imageUrl = "",
            linkUrl = "",
        ),
    )

// Overview fixture: no chapters, no AI tabs → tab strip is absent and the
// screen renders header + blurb + action row only. This isolates the
// per-size centered-column width cap (the focus of the Phase 9 tablet
// snapshots) from tab-body churn that Phase 10 will own.
private val OVERVIEW_FIXTURE: EpisodeDetailUiState =
    EpisodeDetailUiState(
        episode = SAMPLE_EPISODE,
        podcast = SAMPLE_PODCAST,
        chapters = emptyList(),
        downloaded = true,
        played = false,
        loading = false,
        summaryEnabled = false,
    )

// Chapters fixture: chapters present, AI tabs off. The visible tab list is
// just [Chapters], so rememberSaveable's default selection lands on Chapters
// without any tab-click simulation.
private val CHAPTERS_FIXTURE: EpisodeDetailUiState =
    OVERVIEW_FIXTURE.copy(chapters = SAMPLE_CHAPTERS)

private const val SAMPLE_PUBLISHED_AT_MS = 1_700_000_000_000L // 2023-11-14
private const val SAMPLE_DURATION_SEC = 67L * 60L
private const val SAMPLE_FILE_SIZE = 48_500_000L
private const val CHAPTER_INTRO_MS = 90_000L
private const val CHAPTER_BODY_MS = 18L * 60L * 1000L
private const val CHAPTER_OUTRO_MS = 60L * 60L * 1000L

private val MASTER_DETAIL_PANE_WIDTH = 754.dp
