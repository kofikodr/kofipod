// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.EpisodeChapter
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailContent
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Visual baselines for the Episode Detail screen. We snapshot the pure-state
 * [EpisodeDetailContent] so the tests don't depend on Koin or any repository
 * wiring. Three configurations cover the structurally different shapes the
 * screen renders:
 *
 *  - downloaded + chapters: the densest layout (chip + DOWNLOADED meta tail
 *    + Trash circle + chapters list).
 *  - not-downloaded + no chapters: the sparsest layout (Download circle, no
 *    chapters section). This catches Spacer / divider regressions on the
 *    minimum-content path.
 *  - mid-download: marks-played already (success-tinted check) but not yet
 *    downloaded — exercises the played-tint branch.
 */
class EpisodeDetailSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun episodeDetail_downloadedWithChapters_light() =
        paparazzi.snapshot {
            ThemedScreen(KofipodThemeMode.Light) { Render(downloadedWithChapters()) }
        }

    @Test
    fun episodeDetail_downloadedWithChapters_dark() =
        paparazzi.snapshot {
            ThemedScreen(KofipodThemeMode.Dark) { Render(downloadedWithChapters()) }
        }

    @Test
    fun episodeDetail_notDownloadedNoChapters_light() =
        paparazzi.snapshot {
            ThemedScreen(KofipodThemeMode.Light) { Render(notDownloadedNoChapters()) }
        }

    @Test
    fun episodeDetail_notDownloadedNoChapters_dark() =
        paparazzi.snapshot {
            ThemedScreen(KofipodThemeMode.Dark) { Render(notDownloadedNoChapters()) }
        }

    @Test
    fun episodeDetail_playedButNotDownloaded_light() =
        paparazzi.snapshot {
            ThemedScreen(KofipodThemeMode.Light) { Render(playedButNotDownloaded()) }
        }
}

@Composable
private fun Render(state: EpisodeDetailUiState) {
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
    )
}

@Composable
private fun ThemedScreen(
    mode: KofipodThemeMode,
    content: @Composable () -> Unit,
) {
    KofipodTheme(mode) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(LocalKofipodColors.current.bg),
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// Sample data — kept inline so the fixtures travel with the test rather than
// being shared across snapshot suites with subtly different needs.
// ---------------------------------------------------------------------------

private fun samplePodcast(category: String = "TECHNOLOGY"): Podcast =
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
        primaryCategory = category,
    )

private fun sampleEpisode(): Episode =
    Episode(
        id = "ep-204",
        podcastId = "pod-1",
        guid = "ep-204-guid",
        title = "Compiler ergonomics: smaller error messages, sharper signals",
        description =
            "<p>This week on the show we talk about the long arc of compiler error " +
                "messages — the work involved in turning a 400-line stack trace into a single " +
                "actionable hint. Visit <a href=\"https://kofipod.app/notes/204\">the show notes</a> " +
                "for links.</p>",
        publishedAt = SAMPLE_PUBLISHED_AT,
        durationSec = SAMPLE_DURATION_SEC,
        enclosureUrl = "https://audio.example/ep-204.mp3",
        enclosureMimeType = "audio/mpeg",
        fileSizeBytes = SAMPLE_FILE_SIZE,
        seasonNumber = 4L,
        episodeNumber = 204L,
        imageUrl = "",
        chaptersUrl = null,
        transcriptUrl = null,
    )

private fun sampleChapters(): List<EpisodeChapter> =
    listOf(
        EpisodeChapter(episodeId = "ep-204", seq = 0L, startMs = 0L, title = "Cold open", imageUrl = "", linkUrl = ""),
        EpisodeChapter(
            episodeId = "ep-204",
            seq = 1L,
            startMs = CHAPTER_INTRO_MS,
            title = "Why error messages got worse before they got better",
            imageUrl = "",
            linkUrl = "",
        ),
        EpisodeChapter(
            episodeId = "ep-204",
            seq = 2L,
            startMs = CHAPTER_BODY_MS,
            title = "What Rust learned from Elm",
            imageUrl = "",
            linkUrl = "",
        ),
        EpisodeChapter(
            episodeId = "ep-204",
            seq = 3L,
            startMs = CHAPTER_OUTRO_MS,
            title = "Outro and notes",
            imageUrl = "",
            linkUrl = "",
        ),
    )

private fun downloadedWithChapters(): EpisodeDetailUiState =
    EpisodeDetailUiState(
        episode = sampleEpisode(),
        podcast = samplePodcast(),
        chapters = sampleChapters(),
        downloaded = true,
        played = false,
        loading = false,
    )

private fun notDownloadedNoChapters(): EpisodeDetailUiState =
    EpisodeDetailUiState(
        episode = sampleEpisode().copy(description = "Quick episode, no chapters and no download yet."),
        podcast = samplePodcast(category = ""),
        chapters = emptyList(),
        downloaded = false,
        played = false,
        loading = false,
    )

private fun playedButNotDownloaded(): EpisodeDetailUiState =
    EpisodeDetailUiState(
        episode = sampleEpisode(),
        podcast = samplePodcast(),
        chapters = emptyList(),
        downloaded = false,
        played = true,
        loading = false,
    )

// `SAMPLE_*` constants pin the fixture to deterministic values — recordings
// would otherwise drift if the locale-formatted date / duration changed run-to-run.
private const val SAMPLE_PUBLISHED_AT = 1_700_000_000_000L // 2023-11-14
private const val SAMPLE_DURATION_SEC = 67L * 60L // 67 minutes
private const val SAMPLE_FILE_SIZE = 48_500_000L // ~48 MB
private const val CHAPTER_INTRO_MS = 90_000L
private const val CHAPTER_BODY_MS = 18L * 60L * 1000L
private const val CHAPTER_OUTRO_MS = 60L * 60L * 1000L
