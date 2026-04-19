// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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

@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenList: (String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStarterPack: () -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    var newListOpen by remember { mutableStateOf(false) }
    var opmlInfoOpen by remember { mutableStateOf(false) }
    var pendingDeletePodcast by remember { mutableStateOf<Podcast?>(null) }
    var pendingDeleteList by remember { mutableStateOf<PodcastList?>(null) }

    val lists: List<PodcastList> = state.groups.mapNotNull { it.list }
    val podcasts: List<Podcast> = state.groups.flatMap { it.podcasts }
    val unfiledCount = podcasts.count { it.listId == null }

    val activeListId: String? =
        lists
            .firstOrNull { l -> podcasts.any { it.listId == l.id } }
            ?.id
            ?: lists.firstOrNull()?.id

    val recent: List<Podcast> =
        podcasts
            .sortedByDescending { it.addedAt }
            .take(3)

    // Tile slot descriptor: either a real list, an unfiled bucket, or the "New list" CTA.
    // Lets the grid iterate uniformly without special-casing indices inline.
    val tiles: List<Tile> =
        buildList {
            lists.forEach { add(Tile.OfList(it)) }
            if (unfiledCount > 0) add(Tile.Unfiled(unfiledCount))
            add(Tile.NewList)
        }

    LazyColumn(
        Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
    ) {
        item { LibraryHeader() }

        if (lists.isEmpty() && podcasts.isEmpty()) {
            item {
                LibraryEmptyState(
                    onFindPodcast = onOpenSearch,
                    onCreateList = { newListOpen = true },
                    onOpenStarterPack = onOpenStarterPack,
                    onImportOpml = { opmlInfoOpen = true },
                )
            }
        } else {
            item { SectionLabel(title = "Your lists", topSpacing = 18.dp) }

            val rows = (tiles.size + 1) / 2
            items(rows) { rowIndex ->
                val left = tiles.getOrNull(rowIndex * 2)
                val right = tiles.getOrNull(rowIndex * 2 + 1)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        tile = left,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        onOpenList = onOpenList,
                        onLongPressList = { pendingDeleteList = it },
                        onCreateList = { newListOpen = true },
                    )
                    TileSlot(
                        modifier = Modifier.weight(1f),
                        tile = right,
                        podcasts = podcasts,
                        activeListId = activeListId,
                        onOpenList = onOpenList,
                        onLongPressList = { pendingDeleteList = it },
                        onCreateList = { newListOpen = true },
                    )
                }
            }

            item { SectionLabel(title = "Recently opened", topSpacing = 20.dp) }

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
                        onLongClick = { pendingDeletePodcast = p },
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

    if (opmlInfoOpen) {
        OpmlComingSoonDialog(onDismiss = { opmlInfoOpen = false })
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

    pendingDeleteList?.let { list ->
        ConfirmDialog(
            title = "Delete list?",
            message = "\"${list.name}\" will be removed. Its podcasts will move to Unfiled.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteList(list.id)
                pendingDeleteList = null
            },
            onDismiss = { pendingDeleteList = null },
        )
    }
}

private sealed interface Tile {
    data class OfList(val list: PodcastList) : Tile

    data class Unfiled(val count: Int) : Tile

    data object NewList : Tile
}

@Composable
private fun LibraryHeader() {
    val c = LocalKofipodColors.current
    Text(
        "Library",
        color = c.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TileSlot(
    modifier: Modifier,
    tile: Tile?,
    podcasts: List<Podcast>,
    activeListId: String?,
    onOpenList: (String?) -> Unit,
    onLongPressList: (PodcastList) -> Unit,
    onCreateList: () -> Unit,
) {
    when (tile) {
        is Tile.OfList -> {
            val count = podcasts.count { it.listId == tile.list.id }
            ListTile(
                modifier = modifier,
                list = tile.list,
                podcastCount = count,
                active = tile.list.id == activeListId,
                seed = tile.list.id.hashCode(),
                onClick = { onOpenList(tile.list.id) },
                onLongClick = { onLongPressList(tile.list) },
            )
        }
        is Tile.Unfiled ->
            UnfiledTile(
                modifier = modifier,
                podcastCount = tile.count,
                onClick = { onOpenList(null) },
            )
        Tile.NewList -> NewListTile(modifier = modifier, onClick = onCreateList)
        null -> Box(modifier = modifier.aspectRatio(1f)) // balances odd-count rows
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListTile(
    modifier: Modifier,
    list: PodcastList,
    podcastCount: Int,
    active: Boolean,
    seed: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
    ) {
        KPIcon(
            name = KPIconName.Folder,
            color = folderColor,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        KofipodArtwork(
            size = 44.dp,
            seed = seed * 7 + 3,
            label = null,
            radius = 22.dp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
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
private fun UnfiledTile(
    modifier: Modifier,
    podcastCount: Int,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        KPIcon(
            name = KPIconName.Folder,
            color = c.textSoft,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                "Unfiled",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$podcastCount PODCASTS",
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun NewListTile(
    modifier: Modifier,
    onClick: () -> Unit,
) {
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

private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.5.dp,
    dashLength: androidx.compose.ui.unit.Dp = 6.dp,
    gapLength: androidx.compose.ui.unit.Dp = 5.dp,
): Modifier =
    this.drawBehind {
        val strokePx = strokeWidth.toPx()
        val radiusPx = cornerRadius.toPx()
        val stroke =
            Stroke(
                width = strokePx,
                pathEffect =
                    PathEffect.dashPathEffect(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
    podcast: Podcast,
    episodeCount: Int,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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

private fun placeholderEpisodeCount(p: Podcast): Int {
    val h = p.id.hashCode()
    val positive = if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h)
    return (positive % 300) + 20
}

@Composable
private fun LibraryEmptyState(
    onFindPodcast: () -> Unit,
    onCreateList: () -> Unit,
    onOpenStarterPack: () -> Unit,
    onImportOpml: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        EmptyHeroCard(onFindPodcast = onFindPodcast, onCreateList = onCreateList)
        SectionLabel(title = "Three ways to start", topSpacing = 22.dp)
        StartActionRow(
            icon = KPIconName.Search,
            iconBg = EmptyStateIconBg.Pink,
            title = "Search the Podcast Index",
            subtitle = "Over 4M shows. Search by title or by person.",
            onClick = onFindPodcast,
        )
        Spacer(Modifier.height(10.dp))
        StartActionRow(
            icon = KPIconName.Radar,
            iconBg = EmptyStateIconBg.Purple,
            title = "Try a starter pack",
            subtitle = "A curated dozen across tech, history, science, and culture.",
            onClick = onOpenStarterPack,
        )
        Spacer(Modifier.height(10.dp))
        StartActionRow(
            icon = KPIconName.Download,
            iconBg = EmptyStateIconBg.Purple,
            title = "Import from OPML",
            subtitle = "Coming from another app? Drop an .opml export in.",
            onClick = onImportOpml,
        )
    }
}

@Composable
private fun EmptyHeroCard(
    onFindPodcast: () -> Unit,
    onCreateList: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val heroGradient =
        Brush.linearGradient(
            colors =
                listOf(
                    c.purpleSoft,
                    c.purple,
                    c.purpleDeep,
                ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(r.lg))
                .background(heroGradient)
                .drawBehind { drawHeroAmbience() },
    ) {
        HeroDecoration(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 22.dp, top = 22.dp),
        )
        HeroSparkAndHalo(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 22.dp, top = 22.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 88.dp, bottom = 22.dp),
        ) {
            val heroHeadline =
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) {
                        append("A clean shelf.\nLet's fill it with ")
                    }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = c.pink)) {
                        append("good stuff.")
                    }
                }
            Text(
                heroHeadline,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                lineHeight = 30.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Organize podcasts into folders — one \"Walks,\" one \"Work,\" one for that weird documentary rabbit hole.",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(r.pill))
                            .background(Color.White)
                            .clickable { onFindPodcast() }
                            .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KPIcon(name = KPIconName.Search, color = c.purpleDeep, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Find your first podcast",
                            color = c.purpleDeep,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable { onCreateList() },
                    contentAlignment = Alignment.Center,
                ) {
                    KPIcon(name = KPIconName.Plus, color = Color.White, size = 20.dp, strokeWidth = 2.4f)
                }
            }
        }
    }
}

/**
 * Two overlapping folder tiles at the top-left of the hero — a translucent-white
 * "back" tile offset up-and-left, and a pink front tile with the folder glyph.
 */
@Composable
private fun HeroDecoration(modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        modifier = modifier.size(width = 96.dp, height = 72.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .align(Alignment.TopStart)
                    .rotate(-10f)
                    .clip(RoundedCornerShape(r.md))
                    .background(Color.White.copy(alpha = 0.24f)),
        )
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .align(Alignment.TopStart)
                    .offset(x = 30.dp, y = 14.dp)
                    .clip(RoundedCornerShape(r.md))
                    .background(c.pink),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Folder, color = Color.White, size = 28.dp, strokeWidth = 2.2f)
        }
    }
}

/**
 * Top-right ornament: a radial halo glow behind a sparkle + a tiny "+" dot.
 * The halo uses the hero's own purpleSoft so it reads as lift, not contrast.
 */
@Composable
private fun HeroSparkAndHalo(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(64.dp)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width * 0.62f
            val cy = size.height * 0.42f
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                        center = Offset(cx, cy),
                        radius = size.minDimension * 0.55f,
                    ),
                radius = size.minDimension * 0.55f,
                center = Offset(cx, cy),
            )
        }
        Text(
            "✦",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 2.dp),
        )
        Text(
            "+",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 6.dp, y = (-6).dp),
        )
    }
}

/**
 * Bottom-right concentric ring decoration drawn into the card gradient.
 * Uses the card's own bottom-right as the center, so only a quarter of each
 * ring is visible — matches the "sound-wave / radar" motif in the mock.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeroAmbience() {
    val cx = size.width * 0.95f
    val cy = size.height * 0.92f
    val base = size.minDimension * 0.18f
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f.dp.toPx())
    listOf(1.0f, 1.7f, 2.4f, 3.1f).forEachIndexed { i, scale ->
        drawCircle(
            color = Color.White.copy(alpha = 0.22f - i * 0.04f),
            radius = base * scale,
            center = Offset(cx, cy),
            style = stroke,
        )
    }
}

private enum class EmptyStateIconBg { Pink, Purple }

@Composable
private fun StartActionRow(
    icon: KPIconName,
    iconBg: EmptyStateIconBg,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val (bg, fg) =
        when (iconBg) {
            EmptyStateIconBg.Pink -> c.pink to Color.White
            EmptyStateIconBg.Purple -> c.purpleTint to c.purple
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(r.md))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(r.md))
                .clickable { onClick() }
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = icon, color = fg, size = 22.dp, strokeWidth = 2.0f)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = c.textMute,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
    }
}

@Composable
private fun OpmlComingSoonDialog(onDismiss: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text(
                "OPML import — coming soon",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "We're working on importing subscriptions from an .opml file. For now, search and save each show, or try a starter pack.",
                color = c.textSoft,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Spacer(Modifier.weight(1f))
                Text(
                    "Got it",
                    color = c.purple,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun NewListDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
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
