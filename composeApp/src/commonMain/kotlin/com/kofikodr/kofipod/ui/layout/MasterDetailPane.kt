// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Two-pane container used by tablet landscape screens (Library, Search, Podcast detail,
 * Settings 10"L). Master pane fills [masterWeight] of the width; detail pane fills the
 * remainder. Detail renders [emptyDetail] when [hasSelection] is false.
 *
 * Phase 1 §7 primitive. Used by Phase 2 (Library, Search), Phase 6 (Settings landscape),
 * and Phase 8 (Podcast detail). A hairline divider painted between the panes keeps the
 * boundary legible against both surfaces without committing to a heavier separator.
 */
@Composable
fun MasterDetailPane(
    master: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
    masterWeight: Float = 0.62f,
    emptyDetail: @Composable () -> Unit = {},
) {
    val c = LocalKofipodColors.current
    Row(modifier.fillMaxSize()) {
        Box(Modifier.weight(masterWeight).fillMaxHeight()) { master() }
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(c.border),
        )
        Box(Modifier.weight(1f - masterWeight).fillMaxHeight()) {
            if (hasSelection) detail() else emptyDetail()
        }
    }
}

/**
 * Centered muted-text hint used by [MasterDetailPane] as the default empty-detail
 * affordance. Standalone so callers can also place it inside custom detail layouts
 * (e.g. a header + EmptyDetailHint stack).
 */
@Composable
fun EmptyDetailHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = c.textMute,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
