// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.db.Podcast
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    var newListOpen by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library",
                    color = c.text,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "+ New list",
                    color = c.pink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { newListOpen = true },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.groups.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Your library is empty", color = c.text, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Search for a podcast and tap \"Save to list\" to get started.",
                        color = c.textMute,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        state.groups.forEach { group ->
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    (group.list?.name ?: "Unfiled").uppercase(),
                    color = c.textMute,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.1.em,
                )
                Spacer(Modifier.height(8.dp))
            }
            group.podcasts.forEach { p ->
                item(key = p.id) {
                    PodcastRow(p, onClick = { onOpenPodcast(p.id) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (group.podcasts.isEmpty()) {
                item {
                    Text("No podcasts yet.", color = c.textMute, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (newListOpen) {
        NewListDialog(
            onDismiss = { newListOpen = false },
            onCreate = {
                viewModel.createList(it)
                newListOpen = false
            },
        )
    }
}

@Composable
private fun PodcastRow(p: Podcast, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = p.artworkUrl.ifBlank { null },
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(r.sm)).background(c.purpleTint),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.author.isNotBlank()) {
                Text(p.author, color = c.textMute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text("New list", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.sm))
                    .background(c.bgSubtle)
                    .padding(12.dp),
            ) {
                if (name.isEmpty()) Text("List name", color = c.textMute)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Text(
                    "Cancel",
                    color = c.textSoft,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp),
                )
                Spacer(Modifier.weight(1f))
                KPButton(label = "Create", onClick = { if (name.isNotBlank()) onCreate(name) })
            }
        }
    }
}

private val Double.em: androidx.compose.ui.unit.TextUnit get() = this.sp
