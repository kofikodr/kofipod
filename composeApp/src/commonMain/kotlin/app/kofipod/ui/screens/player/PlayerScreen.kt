// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val p = state.player

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val dismissConnection =
        remember(onBack) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val current = dragOffset.value
                    if (current > 0f && available.y < 0f) {
                        val toConsume = maxOf(available.y, -current)
                        scope.launch { dragOffset.snapTo(current + toConsume) }
                        return Offset(0f, toConsume)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f) {
                        scope.launch { dragOffset.snapTo(dragOffset.value + available.y * 0.5f) }
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (dragOffset.value >= dismissThresholdPx) {
                        onBack()
                    } else if (dragOffset.value > 0f) {
                        dragOffset.animateTo(0f)
                    }
                    return Velocity.Zero
                }
            }
        }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .nestedScroll(dismissConnection)
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
