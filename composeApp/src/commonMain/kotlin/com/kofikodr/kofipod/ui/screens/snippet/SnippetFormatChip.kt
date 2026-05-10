// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.snippets.SnippetFormat
import com.kofikodr.kofipod.snippets.SnippetSizeEstimator
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Two-segment MP4 / MP3 selector. Active segment uses a white surface with a
 * lavender border + dark text + pink size, per the Slice 4 design. Inactive
 * segment is transparent with muted text.
 */
@Composable
fun SnippetFormatChip(
    selected: SnippetFormat,
    durationMs: Long,
    onSelect: (SnippetFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Column(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(c.surfaceAlt)
                .border(1.dp, c.border, RoundedCornerShape(28.dp))
                .padding(4.dp),
        ) {
            for (format in SnippetFormat.entries) {
                val active = format == selected
                val size = SnippetSizeEstimator.formatBytes(SnippetSizeEstimator.estimateBytes(format, durationMs))
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) c.surface else Color.Transparent)
                        .let { if (active) it.border(1.dp, c.borderStrong, RoundedCornerShape(24.dp)) else it }
                        .clickable { onSelect(format) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        format.name,
                        color = if (active) c.text else c.textMute,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        " ~$size",
                        color = if (active) c.pink else c.textMute,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "MP4 includes a generated waveform card with the show art.",
            color = c.textMute,
            fontSize = 12.sp,
        )
    }
}
