// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.kofipod.playback.KofipodPlayer
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val player = koinInject<KofipodPlayer>()
    val state by player.state.collectAsState()
    if (state.episodeId == null) return
    val c = LocalKofipodColors.current
    val progressFraction =
        if (state.durationMs > 0L) {
            (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind {
                if (progressFraction > 0f) {
                    drawRect(
                        color = c.purpleTint,
                        size = Size(size.width * progressFraction, size.height),
                    )
                }
            }
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("miniPlayer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.kofipod.ui.primitives.KofipodArtwork(
            size = 40.dp,
            seed = state.episodeId?.hashCode() ?: 0,
            label = state.title,
            radius = 8.dp,
            model = state.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = state.title.ifBlank { state.episodeId.orEmpty() },
            color = c.text,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.pink)
                .clickable {
                    if (state.isPlaying) player.pause() else player.resume()
                },
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
