// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.db.Episode
import app.kofipod.db.EpisodeChapter
import app.kofipod.db.Podcast
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.screens.detail.ai.AiSummaryPanel
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EpisodeDetailScreen(
    episodeId: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenAiSetup: () -> Unit,
    viewModel: EpisodeDetailViewModel = koinViewModel(parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    EpisodeDetailContent(
        state = state,
        onBack = onBack,
        onShare = viewModel::share,
        onPlay = {
            viewModel.togglePlay()
            if (!state.isCurrentEpisode) onOpenPlayer()
        },
        onMarkPlayed = viewModel::markPlayed,
        onDeleteDownload = viewModel::deleteDownload,
        onDownload = viewModel::download,
        onChapterTap = { startMs ->
            viewModel.seekToChapter(startMs)
            if (!state.isCurrentEpisode) onOpenPlayer()
        },
        onOpenAiSetup = onOpenAiSetup,
    )
}

/**
 * Pure-state rendering of the Episode Detail screen. Lives separately from
 * [EpisodeDetailScreen] so paparazzi snapshots can drive the screen with
 * hand-rolled [EpisodeDetailUiState] instances without standing up Koin.
 */
@Composable
internal fun EpisodeDetailContent(
    state: EpisodeDetailUiState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPlay: () -> Unit,
    onMarkPlayed: () -> Unit,
    onDeleteDownload: () -> Unit,
    onDownload: () -> Unit,
    onChapterTap: (Long) -> Unit,
    onOpenAiSetup: () -> Unit,
) {
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        TopBar(onBack = onBack, onShare = onShare)

        when {
            state.episode == null && !state.loading -> {
                Spacer(Modifier.height(40.dp))
                Text(
                    state.error ?: "Episode not found",
                    color = c.textMute,
                    fontSize = 14.sp,
                )
            }
            state.episode != null -> {
                EpisodeBody(
                    episode = state.episode,
                    podcast = state.podcast,
                    chapters = state.chapters,
                    summaryEnabled = state.summaryEnabled,
                    isPlayingThis = state.isPlayingThis,
                    isCurrentEpisode = state.isCurrentEpisode,
                    downloaded = state.downloaded,
                    played = state.played,
                    onPlay = onPlay,
                    onMarkPlayed = onMarkPlayed,
                    onDeleteDownload = onDeleteDownload,
                    onDownload = onDownload,
                    onChapterTap = onChapterTap,
                    onOpenAiSetup = onOpenAiSetup,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(onClick = onBack) { KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp) }
        Spacer(Modifier.weight(1f))
        IconBox(onClick = onShare) {
            KPIcon(name = KPIconName.Share, color = c.text, size = 20.dp, strokeWidth = 1.6f)
        }
    }
}

@Composable
private fun IconBox(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun EpisodeBody(
    episode: Episode,
    podcast: Podcast?,
    chapters: List<EpisodeChapter>,
    summaryEnabled: Boolean,
    isPlayingThis: Boolean,
    isCurrentEpisode: Boolean,
    downloaded: Boolean,
    played: Boolean,
    onPlay: () -> Unit,
    onMarkPlayed: () -> Unit,
    onDeleteDownload: () -> Unit,
    onDownload: () -> Unit,
    onChapterTap: (Long) -> Unit,
    onOpenAiSetup: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Spacer(Modifier.height(8.dp))

    PodcastStrip(podcast = podcast, episodeNumber = episode.episodeNumber?.toInt())

    val category = podcast?.primaryCategory?.takeIf { it.isNotBlank() }
    if (category != null) {
        Spacer(Modifier.height(12.dp))
        CategoryChip(label = category)
    }

    Spacer(Modifier.height(12.dp))
    Text(
        episode.title,
        color = c.text,
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeight = 32.sp,
    )

    Spacer(Modifier.height(10.dp))
    MetaLine(episode = episode, downloaded = downloaded)

    Spacer(Modifier.height(16.dp))
    ActionRow(
        isPlayingThis = isPlayingThis,
        isCurrentEpisode = isCurrentEpisode,
        downloaded = downloaded,
        canDownload = episode.enclosureUrl.isNotBlank(),
        played = played,
        onPlay = onPlay,
        onMarkPlayed = onMarkPlayed,
        onDeleteDownload = onDeleteDownload,
        onDownload = onDownload,
    )

    val description = remember(episode.description) { renderDescription(episode.description) }
    if (description.text.isNotBlank()) {
        // Gate on the rendered text, not raw — a description that's only HTML
        // (e.g. "<p></p>") would otherwise render an empty paragraph block.
        Spacer(Modifier.height(20.dp))
        Text(
            description,
            color = c.text,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }

    val visibleTabs =
        remember(chapters.size, summaryEnabled) {
            buildVisibleTabs(chapterCount = chapters.size, summaryEnabled = summaryEnabled)
        }
    if (visibleTabs.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        var selected by rememberSaveable {
            mutableStateOf(
                visibleTabs.firstOrNull { it == EpisodeDetailTab.Summary } ?: visibleTabs.first(),
            )
        }
        // The tab list shrinks dynamically (e.g. user disconnects key while screen is up).
        // Snap selection back to a still-present tab so we don't render an empty content area.
        if (selected !in visibleTabs) {
            selected = visibleTabs.first()
        }
        EpisodeDetailTabRow(
            tabs = visibleTabs,
            selected = selected,
            onSelect = { selected = it },
            chapterCount = chapters.size,
            // Slice 3 fills this in.
            mentionedCount = 0,
        )
        Spacer(Modifier.height(16.dp))
        when (selected) {
            EpisodeDetailTab.Chapters -> ChaptersSection(chapters = chapters, onChapterTap = onChapterTap)
            EpisodeDetailTab.Summary ->
                AiSummaryPanel(
                    episodeId = episode.id,
                    audioMinutes = (episode.durationSec / 60).toInt(),
                    onOpenAiSetup = onOpenAiSetup,
                )
            EpisodeDetailTab.Mentioned -> ComingSoonCard(label = "Mentioned guests, books, and links land in a future update.")
            EpisodeDetailTab.Discuss -> ComingSoonCard(label = "Ask Gemini about this episode — coming soon.")
        }
    }
}

@Composable
private fun ComingSoonCard(label: String) {
    val c = LocalKofipodColors.current
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(20.dp),
    ) {
        Text(
            label,
            color = c.textMute,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun ChaptersSection(
    chapters: List<EpisodeChapter>,
    onChapterTap: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "CHAPTERS",
            color = c.pink,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        Text(
            chapters.size.toString(),
            color = c.textMute,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth()) {
        chapters.forEachIndexed { idx, chapter ->
            ChapterRow(chapter = chapter, onClick = { onChapterTap(chapter.startMs) })
            if (idx != chapters.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(c.border),
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: EpisodeChapter,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatChapterTime(chapter.startMs),
            color = c.textMute,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            // 64dp fits HH:MM:SS at 12sp monospace; the previous 56dp wrapped
            // at the 1-hour mark and split "01:00:00" across two lines.
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            chapter.title,
            color = c.text,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatChapterTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        h.toString().padStart(2, '0') + ":" +
            m.toString().padStart(2, '0') + ":" +
            s.toString().padStart(2, '0')
    } else {
        m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    }
}

@Composable
private fun CategoryChip(label: String) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, c.pink, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = c.pink,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PodcastStrip(
    podcast: Podcast?,
    episodeNumber: Int?,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        KofipodArtwork(
            size = 36.dp,
            seed = (podcast?.id?.hashCode() ?: 0),
            label = podcast?.title?.take(2),
            radius = 10.dp,
            model = podcast?.artworkUrl?.takeIf { it.isNotBlank() },
            contentDescription = podcast?.title,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            podcast?.title ?: "",
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (episodeNumber != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "EP $episodeNumber",
                color = c.textMute,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MetaLine(
    episode: Episode,
    downloaded: Boolean,
) {
    val c = LocalKofipodColors.current
    val base = episodeMetaLine(episode.publishedAt, episode.durationSec.toInt(), episode.fileSizeBytes)
    val text = if (downloaded) "$base  ·  DOWNLOADED" else base
    Text(
        text,
        color = c.textMute,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun ActionRow(
    isPlayingThis: Boolean,
    isCurrentEpisode: Boolean,
    downloaded: Boolean,
    canDownload: Boolean,
    played: Boolean,
    onPlay: () -> Unit,
    onMarkPlayed: () -> Unit,
    onDeleteDownload: () -> Unit,
    onDownload: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KPButton(
            label = playButtonLabel(isPlayingThis = isPlayingThis, isCurrentEpisode = isCurrentEpisode),
            onClick = onPlay,
            style = KPButtonStyle.PrimaryPink,
            modifier = Modifier.weight(1f).testTag("episodePlayButton"),
        )
        CircleAction(
            icon = KPIconName.Check,
            tint = if (played) c.success else c.pink,
            background = c.pinkSoft,
            onClick = onMarkPlayed,
            testTag = "episodeMarkPlayedButton",
        )
        when (tertiaryAction(downloaded = downloaded, canDownload = canDownload)) {
            TertiaryAction.Delete ->
                CircleAction(
                    icon = KPIconName.Trash,
                    tint = c.danger,
                    background = c.pinkSoft,
                    onClick = onDeleteDownload,
                    testTag = "episodeDeleteDownloadButton",
                )
            TertiaryAction.Download ->
                CircleAction(
                    icon = KPIconName.Download,
                    tint = c.pink,
                    background = c.pinkSoft,
                    onClick = onDownload,
                    testTag = "episodeDownloadButton",
                )
            TertiaryAction.Hidden -> Unit
        }
    }
}

/**
 * Resolves the play button's label from the player + current-episode state. Pure so
 * [EpisodeDetailActionRowTest] can pin all three branches without a composition.
 */
internal fun playButtonLabel(
    isPlayingThis: Boolean,
    isCurrentEpisode: Boolean,
): String =
    when {
        isPlayingThis -> "Pause"
        isCurrentEpisode -> "Resume"
        else -> "Play episode"
    }

/**
 * The rightmost action-row circle has three states: Trash if the user has a local
 * download to remove, Download if there's a fetchable enclosure but no local copy,
 * or hidden when neither applies (e.g. an episode whose feed lacks an enclosure URL).
 */
internal enum class TertiaryAction { Delete, Download, Hidden }

internal fun tertiaryAction(
    downloaded: Boolean,
    canDownload: Boolean,
): TertiaryAction =
    when {
        downloaded -> TertiaryAction.Delete
        canDownload -> TertiaryAction.Download
        else -> TertiaryAction.Hidden
    }

@Composable
private fun CircleAction(
    icon: KPIconName,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, LocalKofipodColors.current.border, CircleShape)
            .clickable { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = icon, color = tint, size = 18.dp, strokeWidth = 1.8f)
    }
}
