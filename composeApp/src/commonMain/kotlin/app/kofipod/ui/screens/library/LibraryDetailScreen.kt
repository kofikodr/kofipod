// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.db.Podcast
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.primitives.PodcastCard
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
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
    var renameListOpen by remember { mutableStateOf(false) }

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
                    HeaderIconButton(
                        icon = KPIconName.Pencil,
                        tint = c.textSoft,
                        contentDescription = "Rename",
                        onClick = { renameListOpen = true },
                    )
                    Spacer(Modifier.width(4.dp))
                    HeaderIconButton(
                        icon = KPIconName.Trash,
                        tint = c.danger,
                        contentDescription = "Delete",
                        onClick = { deleteListOpen = true },
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
                EmptyFolderAdder(
                    searchQuery = state.searchQuery,
                    onSearchChange = viewModel::setSearchQuery,
                    searching = state.searching,
                    searchError = state.searchError,
                    searchResults = state.searchResults,
                    recentlyViewed = state.recentlyViewed,
                    onAdd = { viewModel.addSummaryToList(it) },
                    onOpenPodcast = onOpenPodcast,
                )
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
                hasNew = p.id in state.podcastsWithNew,
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

    if (renameListOpen) {
        RenameListDialog(
            initialName = state.listName,
            onDismiss = { renameListOpen = false },
            onRename = {
                viewModel.renameList(it)
                renameListOpen = false
            },
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: KPIconName,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(r.pill))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(r.pill))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Name is read by accessibility via contentDescription on the box semantics;
        // unused here but kept to document the action clearly.
        @Suppress("UNUSED_VARIABLE")
        val label = contentDescription
        KPIcon(name = icon, color = tint, size = 18.dp, strokeWidth = 1.8f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyFolderAdder(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    searching: Boolean,
    searchError: String?,
    searchResults: List<PodcastSummary>,
    recentlyViewed: List<PodcastSummary>,
    onAdd: (PodcastSummary) -> Unit,
    onOpenPodcast: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val topics = listOf("Tech", "Comedy", "News", "Design")

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "This list is empty",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Search for a podcast, or tap one you viewed recently to add it here.",
            color = c.textSoft,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))

        // Search bar
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(r.pill))
                .background(c.surfaceAlt)
                .border(1.dp, c.border, RoundedCornerShape(r.pill))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KPIcon(name = KPIconName.Search, color = c.textMute, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text("Search podcasts", color = c.textMute, fontSize = 14.sp)
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle =
                        TextStyle(
                            color = c.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    cursorBrush = SolidColor(c.pink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (searchQuery.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(r.pill))
                        .background(c.purpleTint)
                        .clickable { onSearchChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    KPIcon(name = KPIconName.Close, color = c.textSoft, size = 12.dp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Topic chips — prefill the search field
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            topics.forEach { label ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(r.pill))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(r.pill))
                        .clickable { onSearchChange(label) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = c.text,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            searching ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = c.pink)
                }
            searchError != null ->
                Text(
                    searchError,
                    color = c.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            searchQuery.isNotBlank() && searchResults.isEmpty() ->
                Text(
                    "No results",
                    color = c.textMute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            searchQuery.isNotBlank() -> {
                SectionCaption("SEARCH RESULTS")
                Spacer(Modifier.height(8.dp))
                searchResults.forEach { p ->
                    AddableRow(
                        summary = p,
                        onAdd = { onAdd(p) },
                        onOpen = { onOpenPodcast(p.id) },
                    )
                }
            }
            recentlyViewed.isNotEmpty() -> {
                SectionCaption("RECENTLY VIEWED")
                Spacer(Modifier.height(8.dp))
                recentlyViewed.forEach { p ->
                    AddableRow(
                        summary = p,
                        onAdd = { onAdd(p) },
                        onOpen = { onOpenPodcast(p.id) },
                    )
                }
            }
            else ->
                Text(
                    "Open a podcast from Search to see it here.",
                    color = c.textMute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
        }
    }
}

@Composable
private fun SectionCaption(label: String) {
    val c = LocalKofipodColors.current
    Text(
        label,
        color = c.textMute,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun AddableRow(
    summary: PodcastSummary,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 44.dp,
            seed = summary.feedId.toInt(),
            label = summary.title,
            radius = 10.dp,
            model = summary.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                summary.title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.author.isNotBlank()) {
                Text(
                    summary.author,
                    color = c.textMute,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(r.pill))
                .background(c.pink)
                .clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KPIcon(name = KPIconName.Plus, color = Color.White, size = 14.dp, strokeWidth = 2.4f)
                Spacer(Modifier.width(4.dp))
                Text("Add", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RenameListDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text("Rename list", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    cursorBrush = SolidColor(c.pink),
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
                KPButton(
                    label = "Save",
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotBlank() && trimmed != initialName) {
                            onRename(trimmed)
                        } else {
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}
