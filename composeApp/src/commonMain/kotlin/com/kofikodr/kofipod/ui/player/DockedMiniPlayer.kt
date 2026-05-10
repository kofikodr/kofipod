// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playback.KofipodPlayer
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

/**
 * Tablet docked mini-player — the "Now playing" strip rendered at the bottom of the
 * scaffold's content column (rail keeps its own background per spec §4). 72 dp tall,
 * spans content width. Reads [KofipodPlayer] state from Koin and self-hides when
 * no episode is active. The [Route.Player] gate is enforced by the scaffold.
 *
 * Phone uses [MiniPlayer] instead — that path is unchanged.
 */
@Composable
fun DockedMiniPlayer(
    size: TabletSize,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = koinInject<KofipodPlayer>()
    val state by player.state.collectAsState()
    if (state.episodeId == null) return
    DockedMiniPlayerContent(
        state = state,
        size = size,
        onOpen = onOpen,
        onPlayPause = { if (state.isPlaying) player.pause() else player.resume() },
        modifier = modifier,
    )
}

/**
 * Stateless body of the docked mini-player — public for snapshot tests. Caller is
 * responsible for the `episodeId == null` self-hide.
 */
@Composable
fun DockedMiniPlayerContent(
    state: PlayerState,
    @Suppress("UNUSED_PARAMETER") size: TabletSize,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val progressFraction =
        if (state.durationMs > 0L) {
            (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(c.surface)
            .drawBehind {
                if (progressFraction > 0f) {
                    drawRect(
                        color = c.purpleTint,
                        size = Size(this.size.width * progressFraction, this.size.height),
                    )
                }
            }
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("dockedMiniPlayer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 40.dp,
            seed = state.episodeId?.hashCode() ?: 0,
            label = state.title,
            radius = 8.dp,
            model = state.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = state.title.ifBlank { state.episodeId.orEmpty() },
                color = c.text,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleLine(state),
                color = c.textMute,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        SpeedChip(state.speed)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.pink)
                .clickable { onPlayPause() }
                .testTag("dockedMiniPlayerPlayPause"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state.isPlaying) "II" else "▶",
                color = c.surface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * "Show · Ep N · MM:SS / MM:SS" — falls back gracefully when fields are missing so the
 * subtitle never renders separator soup like "·  · ".
 */
private fun subtitleLine(state: PlayerState): String {
    val parts = mutableListOf<String>()
    if (state.podcastTitle.isNotBlank()) parts += state.podcastTitle
    state.episodeNumber?.let { parts += "Ep $it" }
    parts += "${formatProgress(state.positionMs)} / ${formatProgress(state.durationMs)}"
    return parts.joinToString(" · ")
}

private fun formatProgress(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val sPad = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sPad" else "$m:$sPad"
}

@Composable
private fun SpeedChip(speed: Float) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.purpleTint)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("dockedMiniPlayerSpeedChip"),
    ) {
        Text(
            text = "${formatSpeedTenths(speed)}×",
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

private fun formatSpeedTenths(speed: Float): String {
    val tenths = ((speed * 10f) + 0.5f).toInt().coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}"
}
