// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.playback.KofipodPlayer
import app.kofipod.ui.theme.LocalKofipodColors
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val player = koinInject<KofipodPlayer>()
    val state by player.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Text(
            "← Back",
            color = c.textSoft,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Now playing",
            color = c.textMute,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        AsyncImage(
            model = state.artworkUrl.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.purpleTint),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = state.title.ifBlank { "—" },
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.podcastTitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.podcastTitle, color = c.textSoft, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))
        val progress =
            if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        Slider(
            value = progress,
            onValueChange = { player.seekTo((it * state.durationMs).toLong()) },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(state.positionMs), color = c.textMute, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatMs(state.durationMs), color = c.textMute, fontSize = 12.sp)
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⟲ 10",
                color = c.text,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { player.skipBack() },
            )
            Box(
                Modifier
                    .size(72.dp)
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
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                )
            }
            Text(
                "30 ⟳",
                color = c.text,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { player.skipForward() },
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val sPadded = if (s < 10) "0$s" else s.toString()
    return "$m:$sPadded"
}
