// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.layout.rememberTabletSize
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    onOpenSnippetEditor: (String) -> Unit,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val ent by viewModel.entitlement.collectAsState()
    val tipDismissed by viewModel.isProTipDismissed.collectAsState()
    val size = rememberTabletSize()

    LaunchedEffect(viewModel) {
        viewModel.snippetEditorRoute.collect { id -> onOpenSnippetEditor(id) }
    }

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

    // Outer container owns drag-to-dismiss + scroll. Phone keeps the original 20dp
    // horizontal page padding; per-row title-block padding for tablets is layered
    // on inside PlayerContent so phone Paparazzi stays byte-identical.
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .offset { IntOffset(0, dragOffset.value.roundToInt()) }
            .nestedScroll(dismissConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        PlayerContent(
            state = state,
            entitlement = ent,
            isProTipDismissed = tipDismissed,
            audioLevels = viewModel.audioLevels,
            size = size,
            onBack = onBack,
            onShare = viewModel::share,
            onGoToPodcast = {
                val pid = state.player.podcastId
                if (pid.isNotBlank()) onOpenPodcast(pid)
            },
            onMarkPlayed = viewModel::markAsPlayed,
            onSeek = viewModel::seekTo,
            onTogglePlay = viewModel::togglePlayPause,
            onSkipBack = viewModel::skipBack,
            onSkipForward = viewModel::skipForward,
            onPrev = viewModel::prev,
            onNext = viewModel::next,
            onSnipTapped = viewModel::onSnipTapped,
            onBookmarkTapped = viewModel::onBookmarkTapped,
            onCycleSpeed = viewModel::cycleSpeed,
            onSetSleep = viewModel::setSleepTimer,
            onDismissProTip = viewModel::dismissProTip,
        )
    }

    state.toast?.let { text ->
        PlayerToast(text = text, onDone = viewModel::dismissToast)
    }
}

/**
 * Stateless body of the Player screen. Wraps the column of rows from artwork down
 * through the pro-tip banner. Phone (`size == null`) is byte-identical to the
 * legacy layout — the row paddings collapse to zero and the artwork falls back to
 * its original `fillMaxWidth(0.5f)` sizing.
 *
 * Tablet branches: artwork is width-capped, the title block (header / scrubber /
 * transport / action strip) gets extra horizontal padding, and the action strip
 * scales its icon size + label visibility per [TabletSize] (see spec §6).
 *
 * **Drag-to-dismiss and the outer `verticalScroll`/`background` chain stay on
 * `PlayerScreen`'s outer Column** — this body must remain stateless and idempotent
 * so Paparazzi can render it without those Animatable / coroutine seams.
 */
@Composable
internal fun PlayerContent(
    state: PlayerUiState,
    entitlement: ProEntitlement,
    isProTipDismissed: Boolean,
    audioLevels: StateFlow<FloatArray>,
    size: TabletSize?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onGoToPodcast: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSnipTapped: () -> Unit,
    onBookmarkTapped: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSetSleep: (Int?) -> Unit,
    onDismissProTip: () -> Unit,
) {
    val p: PlayerState = state.player

    // Tablet landscape uses a dedicated two-column layout (artwork left, title block
    // right) per the design mocks. Phone + tablet portrait stay on the stacked layout
    // below so their Paparazzi baselines are unaffected.
    if (size != null && size.isMasterDetail) {
        PlayerLandscapeContent(
            state = state,
            entitlement = entitlement,
            isProTipDismissed = isProTipDismissed,
            audioLevels = audioLevels,
            size = size,
            onBack = onBack,
            onShare = onShare,
            onGoToPodcast = onGoToPodcast,
            onMarkPlayed = onMarkPlayed,
            onSeek = onSeek,
            onTogglePlay = onTogglePlay,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onPrev = onPrev,
            onNext = onNext,
            onSnipTapped = onSnipTapped,
            onBookmarkTapped = onBookmarkTapped,
            onCycleSpeed = onCycleSpeed,
            onSetSleep = onSetSleep,
            onDismissProTip = onDismissProTip,
        )
        return
    }

    // size-derived layout knobs (phone = unchanged).
    val artworkMaxWidth: Dp? =
        when (size) {
            TabletSize.Tablet8Port, TabletSize.Tablet8Land -> 360.dp
            TabletSize.Tablet10Port -> 480.dp
            TabletSize.Tablet10Land -> 560.dp
            null -> null
        }
    // Title block extra horizontal padding **on top of** the outer column's 20dp.
    // Targets: 32dp total on 8" (extra=12), 64dp on 10" (extra=44). Phone = 0.
    val titleBlockExtraPadding: Dp =
        when (size) {
            TabletSize.Tablet8Port, TabletSize.Tablet8Land -> 12.dp
            TabletSize.Tablet10Port, TabletSize.Tablet10Land -> 44.dp
            null -> 0.dp
        }
    val actionIconSize: Dp =
        when (size) {
            TabletSize.Tablet8Port, TabletSize.Tablet8Land -> 28.dp
            TabletSize.Tablet10Port, TabletSize.Tablet10Land -> 32.dp
            null -> 24.dp
        }
    // Phone keeps its current behavior (labels visible). 10" landscape mirrors that;
    // the smaller / portrait tablet sizes hide labels per the design mocks.
    val actionShowLabels: Boolean =
        when (size) {
            null, TabletSize.Tablet10Land -> true
            TabletSize.Tablet8Port, TabletSize.Tablet8Land, TabletSize.Tablet10Port -> false
        }

    Spacer(Modifier.height(16.dp))
    PlayerTopBar(
        podcastTitle = p.podcastTitle,
        onBack = onBack,
        onShare = onShare,
        onGoToPodcast = onGoToPodcast,
        onMarkPlayed = onMarkPlayed,
    )
    Spacer(Modifier.height(16.dp))
    PlayerArtworkCard(
        seed = p.episodeId?.hashCode() ?: 0,
        imageUrl = p.artworkUrl,
        podcastTitle = p.podcastTitle,
        episodeNumber = p.episodeNumber,
        isPlaying = p.isPlaying,
        audioLevels = audioLevels,
        artworkMaxWidth = artworkMaxWidth,
    )
    Spacer(Modifier.height(20.dp))
    // Phone (no extra padding) inlines the rows under the outer Column so the
    // legacy Paparazzi baseline stays byte-identical — no nested Column in the
    // layout tree. Tablet sizes wrap the title block in a padded Column.
    if (titleBlockExtraPadding == 0.dp) {
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
            bufferedMs = p.bufferedMs,
            onSeek = onSeek,
            isLocalSource = p.isLocalSource,
        )
        Spacer(Modifier.height(20.dp))
        PlayerTransport(
            isPlaying = p.isPlaying,
            skipBackSec = state.skipBackSec,
            skipForwardSec = state.skipForwardSec,
            hasPrev = state.hasPrev,
            hasNext = state.hasNext,
            onTogglePlay = onTogglePlay,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onPrev = onPrev,
            onNext = onNext,
        )
        Spacer(Modifier.height(20.dp))
        PlayerActionStrip(
            entitlement = entitlement,
            speed = p.speed,
            sleepRemainingMs = p.sleepRemainingMs,
            onSnipTapped = onSnipTapped,
            onBookmarkTapped = onBookmarkTapped,
            onCycleSpeed = onCycleSpeed,
            onSetSleep = onSetSleep,
            iconSize = actionIconSize,
            showLabels = actionShowLabels,
        )
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = titleBlockExtraPadding)) {
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
                bufferedMs = p.bufferedMs,
                onSeek = onSeek,
                isLocalSource = p.isLocalSource,
            )
            Spacer(Modifier.height(20.dp))
            PlayerTransport(
                isPlaying = p.isPlaying,
                skipBackSec = state.skipBackSec,
                skipForwardSec = state.skipForwardSec,
                hasPrev = state.hasPrev,
                hasNext = state.hasNext,
                onTogglePlay = onTogglePlay,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPrev = onPrev,
                onNext = onNext,
            )
            Spacer(Modifier.height(20.dp))
            PlayerActionStrip(
                entitlement = entitlement,
                speed = p.speed,
                sleepRemainingMs = p.sleepRemainingMs,
                onSnipTapped = onSnipTapped,
                onBookmarkTapped = onBookmarkTapped,
                onCycleSpeed = onCycleSpeed,
                onSetSleep = onSetSleep,
                iconSize = actionIconSize,
                showLabels = actionShowLabels,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    PlayerProTipBanner(
        visible = !isProTipDismissed,
        onDismiss = onDismissProTip,
    )
    Spacer(Modifier.height(32.dp))
}

/**
 * Tablet landscape body: artwork on the left, metadata + scrubber + transport +
 * action strip on the right. Matches the "Horizontal Playing" design mock.
 *
 * Top bar is rendered above the two-column row so the chevron-down / overflow menu
 * stay in their familiar positions. The pro-tip banner runs full-width below the
 * two columns. Drag-to-dismiss + verticalScroll stay on the outer [PlayerScreen]
 * column — this body is stateless for Paparazzi.
 */
@Composable
private fun PlayerLandscapeContent(
    state: PlayerUiState,
    entitlement: ProEntitlement,
    isProTipDismissed: Boolean,
    audioLevels: StateFlow<FloatArray>,
    size: TabletSize,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onGoToPodcast: () -> Unit,
    onMarkPlayed: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSnipTapped: () -> Unit,
    onBookmarkTapped: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSetSleep: (Int?) -> Unit,
    onDismissProTip: () -> Unit,
) {
    val p: PlayerState = state.player
    val isTenInch = size == TabletSize.Tablet10Land

    val artworkMaxWidth: Dp = if (isTenInch) 480.dp else 360.dp
    val titleFontSize = if (isTenInch) 32.sp else 26.sp
    val titleLineHeight = if (isTenInch) 38.sp else 32.sp
    val columnGap = if (isTenInch) 40.dp else 24.dp
    val actionIconSize: Dp = if (isTenInch) 32.dp else 28.dp

    Spacer(Modifier.height(16.dp))
    PlayerTopBar(
        podcastTitle = p.podcastTitle,
        onBack = onBack,
        onShare = onShare,
        onGoToPodcast = onGoToPodcast,
        onMarkPlayed = onMarkPlayed,
    )
    Spacer(Modifier.height(24.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(columnGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left column: artwork centered within the weighted column. `PlayerArtworkCard`
        // applies the width cap itself via its `artworkMaxWidth` param and centers the
        // square inside a `fillMaxWidth` Box, so no outer wrapper is needed here.
        Column(modifier = Modifier.weight(1f)) {
            PlayerArtworkCard(
                seed = p.episodeId?.hashCode() ?: 0,
                imageUrl = p.artworkUrl,
                podcastTitle = p.podcastTitle,
                episodeNumber = p.episodeNumber,
                isPlaying = p.isPlaying,
                audioLevels = audioLevels,
                artworkMaxWidth = artworkMaxWidth,
            )
        }
        // Right column: title block + scrubber + transport + actions.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            PlayerHeader(
                episodeNumber = p.episodeNumber,
                durationMs = p.durationMs,
                title = p.title,
                podcastTitle = p.podcastTitle,
                centered = false,
                titleFontSize = titleFontSize,
                titleLineHeight = titleLineHeight,
            )
            Spacer(Modifier.height(20.dp))
            PlayerScrubber(
                positionMs = p.positionMs,
                durationMs = p.durationMs,
                bufferedMs = p.bufferedMs,
                onSeek = onSeek,
                isLocalSource = p.isLocalSource,
            )
            Spacer(Modifier.height(8.dp))
            PlayerTransport(
                isPlaying = p.isPlaying,
                skipBackSec = state.skipBackSec,
                skipForwardSec = state.skipForwardSec,
                hasPrev = state.hasPrev,
                hasNext = state.hasNext,
                onTogglePlay = onTogglePlay,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPrev = onPrev,
                onNext = onNext,
            )
            Spacer(Modifier.height(20.dp))
            PlayerActionStrip(
                entitlement = entitlement,
                speed = p.speed,
                sleepRemainingMs = p.sleepRemainingMs,
                onSnipTapped = onSnipTapped,
                onBookmarkTapped = onBookmarkTapped,
                onCycleSpeed = onCycleSpeed,
                onSetSleep = onSetSleep,
                iconSize = actionIconSize,
                showLabels = true,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    PlayerProTipBanner(
        visible = !isProTipDismissed,
        onDismiss = onDismissProTip,
    )
    Spacer(Modifier.height(32.dp))
}

/**
 * Snapshot-only helper: an inert `StateFlow<FloatArray>` for Paparazzi harnesses
 * that need to render [PlayerContent] without a live player. Kept out of the public
 * API surface — internal to make it callable from `androidUnitTest` snapshot tests.
 */
internal fun emptyAudioLevelsForPreview(): StateFlow<FloatArray> = MutableStateFlow(FloatArray(24) { 0f })
