// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.db.Podcast
import app.kofipod.ui.primitives.PodcastCard
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LibraryDetailScreen(
    listId: String?,
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    viewModel: LibraryDetailViewModel = koinViewModel { parametersOf(listId) },
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    // If the folder was just deleted (state.gone), pop back.
    LaunchedEffect(state.gone) { if (state.gone) onBack() }

    var pendingDeletePodcast by remember { mutableStateOf<Podcast?>(null) }
    var deleteListOpen by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("← Back", color = c.textSoft, modifier = Modifier.clickable { onBack() })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.listName.ifBlank { "List" },
                    color = c.text,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    modifier = Modifier.weight(1f),
                )
                if (state.listId != null) {
                    Text(
                        "Delete",
                        color = c.danger,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { deleteListOpen = true },
                    )
                }
            }
        }
        item {
            val count = state.podcasts.size
            Text(
                "$count ${if (count == 1) "podcast" else "podcasts"}",
                color = c.textMute,
                fontSize = 12.sp,
            )
        }

        if (state.podcasts.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No podcasts in this list.", color = c.textMute)
                }
            }
        }

        items(state.podcasts, key = { it.id }) { p ->
            PodcastCard(
                title = p.title,
                author = p.author,
                description = p.description,
                seed = p.id.toIntOrNull() ?: p.id.hashCode(),
                artworkUrl = p.artworkUrl,
                onClick = { onOpenPodcast(p.id) },
                onLongClick = { pendingDeletePodcast = p },
            )
        }
    }

    pendingDeletePodcast?.let { p ->
        ConfirmDialog(
            title = "Remove from library?",
            message = "\"${p.title}\" and its episodes will be removed from your library.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.deletePodcast(p.id)
                pendingDeletePodcast = null
            },
            onDismiss = { pendingDeletePodcast = null },
        )
    }

    if (deleteListOpen) {
        ConfirmDialog(
            title = "Delete list?",
            message = "\"${state.listName}\" will be removed. Its podcasts will move to Unfiled.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteList()
                deleteListOpen = false
            },
            onDismiss = { deleteListOpen = false },
        )
    }
}
