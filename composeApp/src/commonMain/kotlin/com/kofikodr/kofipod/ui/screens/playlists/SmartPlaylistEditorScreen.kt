// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.playlists

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playlists.PlayState
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Smart Playlist editor — create-mode (`playlistId == null`) or edit-mode (id set).
 *
 * Pragmatic v1 layout: fixed chip rows for each predicate dimension. The richer dynamic
 * "Add condition / Sort By / exclude tags" UI from the design tile is intentionally
 * deferred to a follow-up slice — the view-model already exposes every dimension as a
 * `toggle*`/`set*` method so the future polish only needs to swap the chrome.
 *
 * Theming follows neighbouring screens: `LocalKofipodColors` / `LocalKofipodRadii` plus
 * literal `fontSize` + `fontWeight` (no `MaterialTheme.typography` — the codebase
 * deliberately avoids it).
 */
@Composable
fun SmartPlaylistEditorScreen(
    playlistId: String?,
    onBack: () -> Unit,
    viewModel: SmartPlaylistEditorViewModel =
        koinViewModel(parameters = { parametersOf(playlistId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        EditorTopBar(
            isSaving = state.isSaving,
            saveEnabled = state.name.trim().isNotEmpty() && !state.isSaving,
            onCancel = onBack,
            onSave = {
                scope.launch {
                    if (viewModel.save()) onBack()
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item { NameField(name = state.name, onChange = viewModel::setName) }

            if (state.saveError != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.saveError ?: "",
                        color = c.danger,
                        fontSize = 13.sp,
                    )
                }
            }

            item { SectionLabel("Match all of") }

            item { StateRow(selected = state.predicate.state, onSelect = viewModel::toggleState) }

            item { SectionLabel("Duration") }
            item {
                DurationRow(
                    minSec = state.predicate.durationRange?.minSec,
                    maxSec = state.predicate.durationRange?.maxSec,
                    onChange = viewModel::setDurationRange,
                )
            }

            item { SectionLabel("Podcasts") }
            item {
                PodcastsRow(
                    podcasts = state.availablePodcasts,
                    selected = state.predicate.podcastIds ?: emptySet(),
                    onToggle = viewModel::togglePodcast,
                )
            }

            item { SectionLabel("Max age") }
            item {
                AgeRow(
                    selectedDays = state.predicate.maxAgeDays,
                    onSelect = viewModel::setMaxAgeDays,
                )
            }

            item { SectionLabel("Other filters") }
            item {
                FlagsRow(
                    hasTranscript = state.predicate.hasTranscript,
                    downloadedOnly = state.predicate.downloadedOnly,
                    hasSnippets = state.predicate.hasSnippets,
                    onCycleTranscript = viewModel::cycleHasTranscript,
                    onToggleDownloaded = viewModel::toggleDownloadedOnly,
                    onCycleSnippets = viewModel::cycleHasSnippets,
                )
            }

            item { SectionLabel("Preview") }
            item { MatchPreviewCard(count = state.matchedCount, titles = state.matchedPreview) }

            if (state.isEditMode) {
                item {
                    Spacer(Modifier.height(24.dp))
                    DeleteButton(onClick = { confirmDelete = true })
                    Spacer(Modifier.height(24.dp))
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
                    scope.launch {
                        viewModel.delete()
                        onBack()
                    }
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    isSaving: Boolean,
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.border, CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Close, color = c.text, size = 16.dp, strokeWidth = 2f)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Smart Playlist",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
        )
        val saveColor = if (saveEnabled) c.purple else c.textMute
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (saveEnabled) c.purpleTint else c.bgSubtle)
                .clickable(enabled = saveEnabled, onClick = onSave)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                if (isSaving) "Saving…" else "Save",
                color = saveColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NameField(
    name: String,
    onChange: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (name.isEmpty()) {
            Text("Playlist name", color = c.textMute, fontSize = 16.sp)
        }
        BasicTextField(
            value = name,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StateRow(
    selected: PlayState?,
    onSelect: (PlayState?) -> Unit,
) {
    val items =
        listOf(
            "Any" to null,
            "Unplayed" to PlayState.Unplayed,
            "In progress" to PlayState.InProgress,
            "Completed" to PlayState.Completed,
        )
    SegmentedChips(items, selected, onSelect)
}

@Composable
private fun AgeRow(
    selectedDays: Int?,
    onSelect: (Int?) -> Unit,
) {
    val items =
        listOf(
            "Any" to null,
            "7 days" to 7,
            "30 days" to 30,
            "90 days" to 90,
        )
    SegmentedChips(items, selectedDays, onSelect)
}

@Composable
private fun <T> SegmentedChips(
    items: List<Pair<String, T?>>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { (label, value) ->
            ChoiceChip(
                label = label,
                selected = value == selected,
                onClick = { onSelect(value) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PodcastsRow(
    podcasts: List<PodcastChoice>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    if (podcasts.isEmpty()) {
        Text("No podcasts in your library yet.", color = c.textMute, fontSize = 13.sp)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        podcasts.forEach { podcast ->
            ChoiceChip(
                label = podcast.title,
                selected = podcast.id in selected,
                onClick = { onToggle(podcast.id) },
            )
        }
    }
}

@Composable
private fun DurationRow(
    minSec: Int?,
    maxSec: Int?,
    onChange: (Int?, Int?) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DurationField(
            label = "Min minutes",
            valueMinutes = minSec?.let { it / SECONDS_PER_MINUTE },
            onChange = { newMinMinutes ->
                onChange(newMinMinutes?.let { it * SECONDS_PER_MINUTE }, maxSec)
            },
            modifier = Modifier.weight(1f),
            colors = c,
            radii = r,
        )
        DurationField(
            label = "Max minutes",
            valueMinutes = maxSec?.let { it / SECONDS_PER_MINUTE },
            onChange = { newMaxMinutes ->
                onChange(minSec, newMaxMinutes?.let { it * SECONDS_PER_MINUTE })
            },
            modifier = Modifier.weight(1f),
            colors = c,
            radii = r,
        )
    }
}

@Composable
private fun DurationField(
    label: String,
    valueMinutes: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier,
    colors: com.kofikodr.kofipod.ui.theme.KofipodColors,
    radii: com.kofikodr.kofipod.ui.theme.KofipodRadii,
) {
    var text by remember(valueMinutes) {
        mutableStateOf(valueMinutes?.toString().orEmpty())
    }
    Box(
        modifier
            .clip(RoundedCornerShape(radii.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(radii.md))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (text.isEmpty()) {
            Text(label, color = colors.textMute, fontSize = 13.sp)
        }
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val sanitized = raw.filter { it.isDigit() }.take(MAX_MINUTE_DIGITS)
                text = sanitized
                onChange(sanitized.toIntOrNull())
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagsRow(
    hasTranscript: Boolean?,
    downloadedOnly: Boolean?,
    hasSnippets: Boolean?,
    onCycleTranscript: () -> Unit,
    onToggleDownloaded: () -> Unit,
    onCycleSnippets: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TriStateChip(label = "Has transcript", value = hasTranscript, onClick = onCycleTranscript)
        ChoiceChip(
            label = "Downloaded only",
            selected = downloadedOnly == true,
            onClick = onToggleDownloaded,
        )
        TriStateChip(label = "Has snippets", value = hasSnippets, onClick = onCycleSnippets)
    }
}

@Composable
private fun TriStateChip(
    label: String,
    value: Boolean?,
    onClick: () -> Unit,
) {
    val suffix =
        when (value) {
            null -> "Any"
            true -> "Yes"
            false -> "No"
        }
    ChoiceChip(label = "$label: $suffix", selected = value != null, onClick = onClick)
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val bg = if (selected) c.purple else c.purpleTint
    val fg = if (selected) c.surface else c.text
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MatchPreviewCard(
    count: Int,
    titles: List<String>,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(16.dp),
    ) {
        Text(
            "Matches $count episode${if (count == 1) "" else "s"}",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        if (titles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            titles.forEach { title ->
                Text(
                    "• $title",
                    color = c.textSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        } else if (count == 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "No episodes match yet — loosen a filter.",
                color = c.textMute,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.bgSubtle)
            .border(1.dp, c.danger, RoundedCornerShape(r.pill))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Delete playlist", color = c.danger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val MAX_MINUTE_DIGITS = 4
