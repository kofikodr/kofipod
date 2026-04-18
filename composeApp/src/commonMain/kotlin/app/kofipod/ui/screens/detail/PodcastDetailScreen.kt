// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.db.PodcastList
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PodcastDetailScreen(
    podcastId: String,
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = koinViewModel { parametersOf(podcastId) },
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val summary = state.summary
    if (summary == null) {
        Box(Modifier.fillMaxSize().background(c.bg), Alignment.Center) {
            if (state.error != null) Text(state.error!!, color = c.danger)
            else Text("Loading…", color = c.textMute)
        }
        return
    }

    var listPickerOpen by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().background(c.bg)) {
        item {
            Column(Modifier.padding(20.dp)) {
                Text("← Back", color = c.textSoft, modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.height(16.dp))
                AsyncImage(
                    model = summary.artworkUrl.ifBlank { null },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                        .clip(RoundedCornerShape(r.lg)).background(c.purpleTint),
                )
                Spacer(Modifier.height(16.dp))
                Text(summary.title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                if (summary.author.isNotBlank()) {
                    Text(summary.author, color = c.textSoft, fontWeight = FontWeight.Medium)
                }
                if (summary.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(summary.description, color = c.textMute, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                val saveLabel = if (state.inLibrary) {
                    state.listId?.let { id ->
                        state.lists.firstOrNull { it.id == id }?.let { "Saved to ${it.name}" }
                    } ?: "Saved (Unfiled)"
                } else "Save to list"
                KPButton(label = saveLabel, onClick = { listPickerOpen = true })
                if (state.inLibrary) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-download new episodes", color = c.text, fontWeight = FontWeight.Medium)
                            Text("On Wi-Fi + charging only", color = c.textMute, fontSize = 12.sp)
                        }
                        Switch(
                            checked = state.autoDownload,
                            onCheckedChange = viewModel::toggleAutoDownload,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.pink,
                                checkedTrackColor = c.pinkSoft,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Episodes", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (state.loading) Text("Loading episodes…", color = c.textMute, fontSize = 12.sp)
                state.error?.let { Text(it, color = c.danger, fontSize = 12.sp) }
            }
        }
        val eps = if (state.inLibrary) {
            state.storedEpisodes.map { EpisodeDisplay(it.id, it.title, (it.durationSec / 60).toInt()) }
        } else {
            state.remoteEpisodes.map { EpisodeDisplay(it.id, it.title, it.durationMinutes) }
        }
        items(eps, key = { it.id }) { ep -> EpisodeRow(ep) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (listPickerOpen) {
        ListPickerDialog(
            lists = state.lists,
            currentListId = state.listId,
            onDismiss = { listPickerOpen = false },
            onPick = {
                viewModel.saveToList(it)
                listPickerOpen = false
            },
        )
    }
}

private data class EpisodeDisplay(val id: String, val title: String, val durationMinutes: Int)

@Composable
private fun EpisodeRow(ep: EpisodeDisplay) {
    val c = LocalKofipodColors.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(ep.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val label = if (ep.durationMinutes > 0) "${ep.durationMinutes} min" else "—"
        Text(label, color = c.textMute, fontSize = 12.sp)
    }
}

@Composable
private fun ListPickerDialog(
    lists: List<PodcastList>,
    currentListId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text("Save to…", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            lists.forEach { list ->
                PickerRow(
                    label = list.name,
                    selected = list.id == currentListId,
                    onClick = { onPick(list.id) },
                )
            }
            PickerRow(
                label = "Unfiled",
                selected = currentListId == null,
                onClick = { onPick(null) },
            )
            if (lists.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No lists yet — tap Unfiled to save without a list (you can create lists in Library).",
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "●" else "○", color = if (selected) c.pink else c.textMute, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.text, fontWeight = FontWeight.Medium)
    }
}
