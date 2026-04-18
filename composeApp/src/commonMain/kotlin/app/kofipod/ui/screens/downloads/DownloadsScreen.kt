// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.db.Download
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    LazyColumn(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(20.dp))
            Text("Downloads", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
        }
        section("Downloading", state.downloading, viewModel::cancel)
        section("Queued / Paused", state.queued, viewModel::cancel)
        section("Completed", state.completed, viewModel::delete, actionLabel = "Delete")
        section("Failed", state.failed, viewModel::delete, actionLabel = "Dismiss")
        if (state.downloading.isEmpty() && state.queued.isEmpty() &&
            state.completed.isEmpty() && state.failed.isEmpty()
        ) {
            item {
                Spacer(Modifier.height(32.dp))
                Text(
                    "No downloads yet. Tap ⤓ on an episode to download.",
                    color = c.textMute,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<Download>,
    onAction: (String) -> Unit,
    actionLabel: String = "Cancel",
) {
    if (items.isEmpty()) return
    item {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title, items.size)
    }
    items(items, key = { it.episodeId }) { d ->
        DownloadRow(d, actionLabel = actionLabel, onAction = { onAction(d.episodeId) })
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val c = LocalKofipodColors.current
    Text(
        "${title.uppercase()} · $count",
        color = c.textMute,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun DownloadRow(d: Download, actionLabel: String, onAction: () -> Unit) {
    val c = LocalKofipodColors.current
    val progress: Float = if (d.totalBytes > 0) {
        (d.downloadedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f)
    } else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.episodeId,
                color = c.text,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                actionLabel,
                color = c.pink,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onAction() },
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c.purpleTint),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.purple),
            )
        }
        Spacer(Modifier.height(4.dp))
        val label = buildString {
            append(formatBytes(d.downloadedBytes))
            if (d.totalBytes > 0) {
                append(" / ")
                append(formatBytes(d.totalBytes))
            }
            d.errorMessage?.let { append(" — ").append(it) }
        }
        Text(label, color = c.textMute, fontSize = 12.sp)
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024 -> "${b / (1024 * 1024)} MB"
    b >= 1024L -> "${b / 1024} KB"
    else -> "$b B"
}
