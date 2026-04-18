// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.window.Dialog
import app.kofipod.db.PodcastList
import app.kofipod.ui.permission.rememberNotificationPermissionRequester
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class DetailTab { Episodes, About }

@Composable
fun PodcastDetailScreen(
    podcastId: String,
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = koinViewModel { parametersOf(podcastId) },
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    val summary = state.summary
    if (summary == null) {
        Box(Modifier.fillMaxSize().background(c.bg), Alignment.Center) {
            if (state.error != null) Text(state.error!!, color = c.danger)
            else Text("Loading…", color = c.textMute)
        }
        return
    }

    var listPickerOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DetailTab.Episodes) }
    var newestFirst by remember { mutableStateOf(true) }

    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        viewModel.toggleNotifyNewEpisodes(granted)
    }

    val listName = state.listId?.let { id -> state.lists.firstOrNull { it.id == id }?.name }
    val saveLabel = when {
        state.inLibrary && listName != null -> "Saved to $listName"
        state.inLibrary -> "Saved"
        else -> "Save to list"
    }

    LazyColumn(Modifier.fillMaxSize().background(c.bg)) {
        item {
            TopIconBar(
                onBack = onBack,
                onShare = { viewModel.sharePodcast() },
            )
        }
        item { HeroRow(summary) }
        if (summary.description.isNotBlank()) {
            item {
                Text(
                    summary.description,
                    color = c.textSoft,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            item { Spacer(Modifier.height(12.dp)) }
        }
        item {
            ActionRow(
                saveLabel = saveLabel,
                saved = state.inLibrary,
                bellOn = state.inLibrary && state.notifyNewEpisodes,
                bellEnabled = state.inLibrary,
                onSave = { listPickerOpen = true },
                onToggleBell = {
                    if (!state.inLibrary) return@ActionRow
                    if (state.notifyNewEpisodes) {
                        viewModel.toggleNotifyNewEpisodes(false)
                    } else {
                        requestNotificationPermission()
                    }
                },
                onDownload = {
                    if (!state.inLibrary) return@ActionRow
                    val newest = state.storedEpisodes.firstOrNull()?.id
                    if (newest != null) viewModel.download(newest)
                },
                downloadEnabled = state.inLibrary,
            )
        }
        if (state.inLibrary) {
            item {
                AutoDownloadRow(
                    enabled = state.autoDownload,
                    onToggle = { viewModel.toggleAutoDownload(it) },
                )
            }
        }
        item { TabsRow(tab = tab, onSelect = { tab = it }, newestFirst = newestFirst, onToggleSort = { newestFirst = !newestFirst }) }

        if (tab == DetailTab.Episodes) {
            val rows: List<EpisodeRowData> = if (state.inLibrary) {
                state.storedEpisodes.map {
                    EpisodeRowData(
                        id = it.id,
                        title = it.title,
                        publishedAt = it.publishedAt,
                        durationSec = it.durationSec.toInt(),
                        fileSizeBytes = it.fileSizeBytes,
                        playable = true,
                        downloadState = state.downloadStates[it.id],
                    )
                }
            } else {
                state.remoteEpisodes.map {
                    EpisodeRowData(
                        id = it.id,
                        title = it.title,
                        publishedAt = 0L,
                        durationSec = it.durationMinutes * 60,
                        fileSizeBytes = 0,
                        playable = it.enclosureUrl.isNotBlank(),
                        downloadState = null,
                    )
                }
            }.let { if (newestFirst) it else it.asReversed() }

            if (state.loading && rows.isEmpty()) {
                item { Text("Loading episodes…", color = c.textMute, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
            }
            state.error?.let { err ->
                item { Text(err, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }

            items(rows, key = { it.id }) { ep ->
                EpisodeRow(
                    ep,
                    canDownload = state.inLibrary,
                    onPlay = { if (ep.playable) viewModel.play(ep.id) },
                    onDownload = { if (ep.playable && state.inLibrary) viewModel.download(ep.id) },
                    onShare = { viewModel.shareEpisode(ep.id) },
                )
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        summary.description.ifBlank { "No description." },
                        color = c.textSoft,
                        fontSize = 14.sp,
                    )
                    if (summary.feedUrl.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            summary.feedUrl,
                            color = c.textMute,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

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

@Composable
private fun TopIconBar(onBack: () -> Unit, onShare: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare) {
            KPIcon(name = KPIconName.Share, color = c.text, size = 20.dp, strokeWidth = 1.6f)
        }
        IconButton(onClick = { /* more not wired */ }) {
            KPIcon(name = KPIconName.More, color = c.text, size = 20.dp, strokeWidth = 1.6f)
        }
    }
}

@Composable
private fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun HeroRow(summary: app.kofipod.domain.PodcastSummary) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KofipodArtwork(
            size = 108.dp,
            seed = summary.feedId.toInt(),
            label = summary.title,
            radius = r.md,
            model = summary.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            if (summary.category.isNotBlank()) {
                Text(
                    summary.category.uppercase(),
                    color = c.pink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.08.em,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                summary.title,
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.author.isNotBlank()) {
                Text(
                    summary.author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
            if (summary.episodeCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${summary.episodeCount} EPS",
                    color = c.textMute,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    saveLabel: String,
    saved: Boolean,
    bellOn: Boolean,
    bellEnabled: Boolean,
    onSave: () -> Unit,
    onToggleBell: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pink pill — prefixed with check when saved
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .background(c.pink)
                .clickable { onSave() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (saved) {
                    KPIcon(name = KPIconName.Check, color = Color.White, size = 16.dp, strokeWidth = 2.4f)
                    Spacer(Modifier.width(8.dp))
                }
                Text(saveLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        val bellTint = when {
            !bellEnabled -> c.textMute
            bellOn -> c.pink
            else -> c.purple
        }
        CircleButton(onClick = onToggleBell, tint = bellTint) {
            KPIcon(name = KPIconName.Bell, color = bellTint, size = 18.dp)
        }
        Spacer(Modifier.width(8.dp))
        CircleButton(onClick = onDownload, tint = if (downloadEnabled) c.purple else c.textMute) {
            KPIcon(
                name = KPIconName.Download,
                color = if (downloadEnabled) c.purple else c.textMute,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun AutoDownloadRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Auto-download new episodes",
                color = c.text,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                "On Wi-Fi while charging",
                color = c.textMute,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.pink,
                checkedTrackColor = c.pinkSoft,
            ),
        )
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, tint: Color, content: @Composable () -> Unit) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.purpleTint)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun TabsRow(
    tab: DetailTab,
    onSelect: (DetailTab) -> Unit,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPill("Episodes", selected = tab == DetailTab.Episodes) { onSelect(DetailTab.Episodes) }
        Spacer(Modifier.width(16.dp))
        TabPill("About", selected = tab == DetailTab.About) { onSelect(DetailTab.About) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clickable { onToggleSort() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (newestFirst) "Newest first" else "Oldest first",
                color = c.textSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
            KPIcon(name = KPIconName.ChevronDown, color = c.textSoft, size = 14.dp)
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) c.text else c.textMute,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) c.pink else Color.Transparent),
        )
    }
}

private data class EpisodeRowData(
    val id: String,
    val title: String,
    val publishedAt: Long,
    val durationSec: Int,
    val fileSizeBytes: Long,
    val playable: Boolean,
    val downloadState: String?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    ep: EpisodeRowData,
    canDownload: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = ep.playable,
                onClick = onPlay,
                onLongClick = onShare,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: circular play button
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.purpleTint)
                .clickable(enabled = ep.playable) { onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Play, color = c.purple, size = 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ep.title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                episodeMetaLine(ep.publishedAt, ep.durationSec, ep.fileSizeBytes),
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Right: state icon
        StateIndicator(ep = ep, canDownload = canDownload, onDownload = onDownload)
    }
}

@Composable
private fun StateIndicator(ep: EpisodeRowData, canDownload: Boolean, onDownload: () -> Unit) {
    val c = LocalKofipodColors.current
    when (ep.downloadState) {
        "Completed" -> Box(Modifier.size(28.dp), Alignment.Center) {
            KPIcon(name = KPIconName.Check, color = c.success, size = 18.dp, strokeWidth = 2.2f)
        }
        "Downloading", "Queued" -> Box(Modifier.size(28.dp), Alignment.Center) {
            KPIcon(name = KPIconName.Clock, color = c.pink, size = 18.dp)
        }
        else -> {
            val active = ep.playable && canDownload
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, c.border, RoundedCornerShape(999.dp))
                    .clickable(enabled = active) { onDownload() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Download,
                    color = if (active) c.textSoft else c.textMute,
                    size = 14.dp,
                    strokeWidth = 1.7f,
                )
            }
        }
    }
}

private fun episodeMetaLine(publishedAt: Long, durationSec: Int, fileSizeBytes: Long): String {
    val parts = mutableListOf<String>()
    if (publishedAt > 0) parts += formatDate(publishedAt)
    if (durationSec > 0) parts += formatDuration(durationSec)
    if (fileSizeBytes > 0) parts += formatMb(fileSizeBytes)
    return if (parts.isEmpty()) "—" else parts.joinToString("  ·  ")
}

private fun formatDate(epochMs: Long): String {
    val ld = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val months = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    return "${months[ld.monthNumber - 1]} ${ld.dayOfMonth.toString().padStart(2, '0')}"
}

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "${mb.toInt()} MB"
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
