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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

/**
 * Library screen: shows the user's lists as a 2-column folder grid followed by a
 * recently-opened rail. Navigation parameters match the NavHost wiring
 * (`LibraryScreen(onOpenPodcast = …)`) to keep the graph compiling.
 */
@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    var newListOpen by remember { mutableStateOf(false) }

    // Derive flat lists/podcasts from the grouped VM state so the screen only
    // needs to read the single `groups` field the VM exposes today.
    val lists: List<PodcastList> = state.groups.mapNotNull { it.list }
    val podcasts: List<Podcast> = state.groups.flatMap { it.podcasts }

    // Active list = first list that actually has podcasts; fallback to first list.
    val activeListId: String? = lists
        .firstOrNull { l -> podcasts.any { it.listId == l.id } }
        ?.id
        ?: lists.firstOrNull()?.id

    // Recently-opened: VM doesn't track opens, so sort all podcasts by addedAt
    // (most recent first) and take 3. Stable fallback when addedAt is unset.
    val recent: List<Podcast> = podcasts
        .sortedByDescending { it.addedAt }
        .take(3)

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
    ) {
        item { LibraryHeader(onNewList = { newListOpen = true }) }

        if (lists.isEmpty() && podcasts.isEmpty()) {
            // Completely empty library: show the centered onboarding block in
            // place of the grid + recently-opened sections.
            item { LibraryEmptyState(onCreateList = { newListOpen = true }) }
        } else {
            item {
                SectionLabel(title = "Your lists", topSpacing = 18.dp)
            }

            // 2-column grid of list tiles + a trailing "New list" tile.
            val tileCount = lists.size + 1
            val rows = (tileCount + 1) / 2
            items(rows) { rowIndex ->
                val leftIndex = rowIndex * 2
                val rightIndex = leftIndex + 1
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        index = leftIndex,
                        lists = lists,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        onCreateList = { newListOpen = true },
                    )
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        index = rightIndex,
                        lists = lists,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        onCreateList = { newListOpen = true },
                    )
                }
            }

            item {
                SectionLabel(title = "Recently opened", topSpacing = 20.dp)
            }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet.",
                        color = c.textMute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(recent.size) { idx ->
                    val p = recent[idx]
                    RecentRow(
                        podcast = p,
                        episodeCount = placeholderEpisodeCount(p),
                        showDivider = idx < recent.lastIndex,
                        onClick = { onOpenPodcast(p.id) },
                    )
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
private fun LibraryHeader(onNewList: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Library",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c.success),
                )
                Spacer(Modifier.width(6.dp))
                // VM doesn't expose a last-sync timestamp, so the subtitle is a
                // stable placeholder that matches the mockup's formatting.
                Text(
                    "Synced recently · Drive",
                    color = c.textMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(c.pink)
                .clickable { onNewList() },
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.Plus,
                color = Color.White,
                size = 26.dp,
                strokeWidth = 2.4f,
            )
        }
    }
}

@Composable
private fun TileSlot(
    modifier: Modifier,
    index: Int,
    lists: List<PodcastList>,
    podcasts: List<Podcast>,
    activeListId: String?,
    onCreateList: () -> Unit,
) {
    when {
        index < lists.size -> {
            val list = lists[index]
            val count = podcasts.count { it.listId == list.id }
            ListTile(
                modifier = modifier,
                list = list,
                podcastCount = count,
                active = list.id == activeListId,
                seed = index,
            )
        }
        index == lists.size -> {
            NewListTile(modifier = modifier, onClick = onCreateList)
        }
        else -> {
            // Keep the grid row balanced when there's an odd count.
            Box(modifier = modifier.aspectRatio(1f))
        }
    }
}

@Composable
private fun ListTile(
    modifier: Modifier,
    list: PodcastList,
    podcastCount: Int,
    active: Boolean,
    seed: Int,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val background = if (active) c.purple else c.surface
    val textColor = if (active) Color.White else c.text
    val subTextColor = if (active) Color.White.copy(alpha = 0.72f) else c.textMute
    val folderColor = if (active) c.pink else c.purple

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r.md))
            .background(background)
            .padding(16.dp),
    ) {
        // Top-left folder icon.
        KPIcon(
            name = KPIconName.Folder,
            color = folderColor,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Top-right decorative gradient peek — small circular artwork stands in
        // for the design's overlapping leaves.
        KofipodArtwork(
            size = 44.dp,
            seed = seed * 7 + 3,
            label = null,
            radius = 22.dp,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // Bottom-left label block.
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                list.name,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$podcastCount PODCASTS",
                color = subTextColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun NewListTile(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r.md))
            .dashedBorder(color = c.borderStrong, cornerRadius = r.md)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KPIcon(
                name = KPIconName.Plus,
                color = c.textSoft,
                size = 26.dp,
                strokeWidth = 2.2f,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "New list",
                color = c.textSoft,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

/** Draws a rounded dashed border inside the layout bounds. */
private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.5.dp,
    dashLength: androidx.compose.ui.unit.Dp = 6.dp,
    gapLength: androidx.compose.ui.unit.Dp = 5.dp,
): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val radiusPx = cornerRadius.toPx()
    val stroke = Stroke(
        width = strokePx,
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), gapLength.toPx()),
            0f,
        ),
    )
    val inset = strokePx / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = stroke,
    )
}

@Composable
private fun RecentRow(
    podcast: Podcast,
    episodeCount: Int,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KofipodArtwork(
                size = 44.dp,
                seed = podcast.id.toIntOrNull() ?: podcast.id.hashCode(),
                label = podcast.title,
                radius = 10.dp,
                model = podcast.artworkUrl.ifBlank { null },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (podcast.author.isNotBlank()) {
                    Text(
                        podcast.author,
                        color = c.textMute,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "$episodeCount EPS",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.border),
            )
        }
    }
}

/**
 * Episode counts aren't tracked on [Podcast] yet, so this returns a deterministic
 * placeholder derived from the podcast id — stable across recompositions so the
 * UI doesn't flicker while real counts are plumbed through later.
 */
private fun placeholderEpisodeCount(p: Podcast): Int {
    val h = p.id.hashCode()
    val positive = if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h)
    return (positive % 300) + 20
}

/**
 * Centered empty-state shown when the user has no lists and no podcasts yet.
 * Replaces the grid + "Recently opened" sections with a single onboarding
 * block that promotes creating the first list.
 */
@Composable
private fun LibraryEmptyState(onCreateList: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.Folder,
                color = c.purple,
                size = 44.dp,
                strokeWidth = 2.0f,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Your shelf is empty",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a list to start collecting. You can also search for a podcast and save it.",
            color = c.textSoft,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(r.pill))
                .background(c.pink)
                .clickable { onCreateList() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Create a list",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "TIP · Lists back up to Drive when signed in",
            color = c.textMute,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
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
