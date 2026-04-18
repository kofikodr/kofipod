// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val p = state.player

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PlayerTopBar(
            podcastTitle = p.podcastTitle,
            onBack = onBack,
            onShare = viewModel::share,
            onGoToPodcast = {
                if (p.podcastId.isNotBlank()) onOpenPodcast(p.podcastId)
            },
            onMarkPlayed = viewModel::markAsPlayed,
        )
        Spacer(Modifier.height(16.dp))
        PlayerArtworkCard(
            seed = p.episodeId?.hashCode() ?: 0,
            imageUrl = p.artworkUrl,
            podcastTitle = p.podcastTitle,
            episodeNumber = p.episodeNumber,
        )
        Spacer(Modifier.height(20.dp))
        PlayerHeader(
            episodeNumber = p.episodeNumber,
            durationMs = p.durationMs,
            title = p.title,
            podcastTitle = p.podcastTitle,
        )
        Spacer(Modifier.height(20.dp))
        PlayerScrubber(
            positionMs = p.positionMs,
            durationMs = p.durationMs,
            onSeek = viewModel::seekTo,
        )
        Spacer(Modifier.height(20.dp))
        PlayerTransport(
            isPlaying = p.isPlaying,
            skipBackSec = state.skipBackSec,
            skipForwardSec = state.skipForwardSec,
            hasPrev = state.hasPrev,
            hasNext = state.hasNext,
            onTogglePlay = viewModel::togglePlayPause,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onPrev = viewModel::prev,
            onNext = viewModel::next,
        )
        Spacer(Modifier.height(20.dp))
        PlayerBottomBar(
            speed = p.speed,
            isPlaying = p.isPlaying,
            sleepRemainingMs = p.sleepRemainingMs,
            onCycleSpeed = viewModel::cycleSpeed,
            onSetSleep = viewModel::setSleepTimer,
        )
        Spacer(Modifier.height(32.dp))
    }

    state.toast?.let { text ->
        PlayerToast(text = text, onDone = viewModel::dismissToast)
    }
}
