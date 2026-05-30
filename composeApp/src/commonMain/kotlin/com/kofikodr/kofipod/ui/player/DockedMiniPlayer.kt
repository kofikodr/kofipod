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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.screens.player.formatMs
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
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = koinInject<Player>()
    val state by player.state.collectAsState()
    if (state.episodeId == null) return
    DockedMiniPlayerContent(
        state = state,
        onOpen = onOpen,
        onPlayPause = { if (state.isPlaying) player.pause() else player.resume() },
        onDismiss = { player.stop() },
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
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
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
        if (!state.isPlaying) {
            // Mirror phone MiniPlayer: only offer dismiss while paused so an in-progress
            // listen can't be ended by a mistaken tap.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        onClickLabel = "Dismiss player",
                        role = Role.Button,
                    ) { onDismiss() }
                    .testTag("dockedMiniPlayerDismiss"),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Close,
                    color = c.textMute,
                    size = 18.dp,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
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
    parts += "${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}"
    return parts.joinToString(" · ")
}
