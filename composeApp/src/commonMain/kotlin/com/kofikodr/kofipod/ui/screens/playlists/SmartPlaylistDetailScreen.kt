// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playlists.EpisodeFacts
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Detail screen for a single Smart Playlist.
 *
 * Surfaces the live matched-episode list (resolver-filtered against the playlist's
 * predicate), with edit / delete affordances on the top bar. Tapping a row navigates
 * to the standard episode detail. When the playlist is deleted out from under the
 * screen (`state.notFound`), we render a tombstone and auto-pop after a short delay
 * so the user isn't stranded.
 */
@Composable
fun SmartPlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    onEdit: (playlistId: String) -> Unit,
    onOpenEpisode: (episodeId: String) -> Unit,
    viewModel: SmartPlaylistDetailViewModel =
        koinViewModel(parameters = { parametersOf(playlistId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    var confirmDelete by remember { mutableStateOf(false) }

    // Auto-pop on tombstone: if the playlist is deleted out from under us, the user
    // shouldn't have to find the back button. 1500ms gives enough time to read the
    // "Playlist deleted" copy without feeling jumpy.
    LaunchedEffect(state.notFound) {
        if (state.notFound) {
            kotlinx.coroutines.delay(TOMBSTONE_AUTO_POP_MS)
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        DetailTopBar(
            title = state.playlist?.name ?: "",
            canEdit = state.playlist != null,
            onBack = onBack,
            onEdit = { state.playlist?.let { onEdit(it.id) } },
            onDelete = { if (state.playlist != null) confirmDelete = true },
        )

        when {
            state.notFound -> TombstoneState(onBack = onBack)
            state.playlist == null -> {
                // Initial empty frame — quickly replaced once the eager state-flow
                // emits its first observation. Render nothing to avoid flicker.
            }
            else -> {
                MatchedCountCard(count = state.matched.size)
                if (state.matched.isEmpty()) {
                    EmptyMatchesState()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.matched, key = { it.episodeId }) { fact ->
                            EpisodeRow(fact = fact, onClick = { onOpenEpisode(fact.episodeId) })
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("This removes the playlist definition. Episodes themselves are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                    onBack()
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canEdit) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Pencil, color = c.text, size = 18.dp)
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Trash, color = c.danger, size = 18.dp)
            }
        }
    }
}

@Composable
private fun MatchedCountCard(count: Int) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.purpleTint)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            "$count matching episode${if (count == 1) "" else "s"}",
            color = c.purple,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EpisodeRow(
    fact: EpisodeFacts,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork is seeded by podcastId so episodes from the same show share a tile.
        // Real podcast artwork lookup belongs to a follow-up enrichment slice; for v1
        // the gradient placeholder is enough to anchor the row visually.
        KofipodArtwork(
            size = 48.dp,
            seed = fact.podcastId.hashCode(),
            label = null,
            radius = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fact.episodeTitle,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fact.durationSec > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    formatDuration(fact.durationSec),
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyMatchesState() {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No matching episodes — try editing the predicate.",
            color = c.textMute,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TombstoneState(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Playlist deleted",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Returning to your library…",
            color = c.textMute,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Back",
            color = c.purple,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onBack).padding(12.dp),
        )
    }
}

private fun formatDuration(durationSec: Int): String {
    val mins = durationSec / SECONDS_PER_MINUTE
    val hours = mins / MINUTES_PER_HOUR
    val remMins = mins % MINUTES_PER_HOUR
    return if (hours > 0) "${hours}h ${remMins}m" else "${mins}m"
}

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val TOMBSTONE_AUTO_POP_MS = 1_500L
