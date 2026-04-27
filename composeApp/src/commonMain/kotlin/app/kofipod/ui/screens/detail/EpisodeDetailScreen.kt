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
import androidx.compose.runtime.remember
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
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EpisodeDetailScreen(
    episodeId: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: EpisodeDetailViewModel = koinViewModel(parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
    ) {
        TopBar(onBack = onBack, onShare = viewModel::share)

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
                    episode = state.episode!!,
                    podcast = state.podcast,
                    chapters = state.chapters,
                    isPlayingThis = state.isPlayingThis,
                    isCurrentEpisode = state.isCurrentEpisode,
                    downloaded = state.downloaded,
                    played = state.played,
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
    isPlayingThis: Boolean,
    isCurrentEpisode: Boolean,
    downloaded: Boolean,
    played: Boolean,
    onPlay: () -> Unit,
    onMarkPlayed: () -> Unit,
    onDeleteDownload: () -> Unit,
    onDownload: () -> Unit,
    onChapterTap: (Long) -> Unit,
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

    if (chapters.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        ChaptersSection(chapters = chapters, onChapterTap = onChapterTap)
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
            modifier = Modifier.width(56.dp),
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
            label =
                when {
                    isPlayingThis -> "Pause"
                    isCurrentEpisode -> "Resume"
                    else -> "Play episode"
                },
            onClick = onPlay,
            style = KPButtonStyle.SecondaryPurple,
            modifier = Modifier.weight(1f).testTag("episodePlayButton"),
        )
        CircleAction(
            icon = KPIconName.Check,
            tint = if (played) c.success else c.purple,
            background = if (played) c.purpleSoft else c.purpleSoft,
            onClick = onMarkPlayed,
            testTag = "episodeMarkPlayedButton",
        )
        if (downloaded) {
            CircleAction(
                icon = KPIconName.Trash,
                tint = c.danger,
                background = c.purpleSoft,
                onClick = onDeleteDownload,
                testTag = "episodeDeleteDownloadButton",
            )
        } else if (canDownload) {
            CircleAction(
                icon = KPIconName.Download,
                tint = c.purple,
                background = c.purpleSoft,
                onClick = onDownload,
                testTag = "episodeDownloadButton",
            )
        }
    }
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
