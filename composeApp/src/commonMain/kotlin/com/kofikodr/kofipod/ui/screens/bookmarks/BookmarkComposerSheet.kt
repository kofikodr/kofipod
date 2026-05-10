// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.bookmarks.BookmarkComposer
import com.kofikodr.kofipod.bookmarks.BookmarkComposerState
import com.kofikodr.kofipod.bookmarks.BookmarkRepository
import com.kofikodr.kofipod.bookmarks.formatBookmarkTimestamp
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPButtonStyle
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

/**
 * Quick-add sheet for the Pro bookmark feature. Hoisted at AppShell so it
 * survives navigation away from the player. Self-gates on
 * [BookmarkComposerState] — when [BookmarkComposerState.Hidden], the function
 * returns before composing the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkComposerSheet() {
    val composer: BookmarkComposer = koinInject()
    val repo: BookmarkRepository = koinInject()
    val state by composer.state.collectAsState()
    val visible = state as? BookmarkComposerState.Visible ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = LocalKofipodColors.current
    // Reset the note field when the snapshot changes (last-write-wins on
    // requestQuickAdd) so the user doesn't see a stale draft from a prior tap.
    var note by remember(visible.episodeId, visible.timestampMs) { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = { composer.cancel() },
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Bookmark", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "${visible.podcastTitle} · ${visible.episodeTitle}",
                color = c.textMute,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatBookmarkTimestamp(visible.timestampMs),
                color = c.purple,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.bg)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (note.isEmpty()) {
                    Text("Add a note (optional)", color = c.textMute, fontSize = 14.sp)
                }
                BasicTextField(
                    value = note,
                    onValueChange = { if (it.length <= NOTE_MAX_CHARS) note = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                KPButton(
                    label = "Cancel",
                    onClick = { composer.cancel() },
                    style = KPButtonStyle.Outline,
                )
                Spacer(Modifier.width(8.dp))
                KPButton(
                    label = "Save",
                    onClick = {
                        repo.add(
                            episodeId = visible.episodeId,
                            podcastId = visible.podcastId,
                            timestampMs = visible.timestampMs,
                            note = note.trim().ifBlank { null },
                            nowMs = Clock.System.now().toEpochMilliseconds(),
                        )
                        composer.cancel()
                    },
                    style = KPButtonStyle.SecondaryPurple,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private const val NOTE_MAX_CHARS = 280
