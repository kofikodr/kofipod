// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetSizeEstimator
import app.kofipod.ui.theme.LocalKofipodColors

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
                .background(c.surface)
                .padding(4.dp),
        ) {
            for (format in SnippetFormat.entries) {
                val active = format == selected
                val size = SnippetSizeEstimator.formatBytes(SnippetSizeEstimator.estimateBytes(format, durationMs))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) c.pink else c.surface)
                        .clickable { onSelect(format) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        format.name,
                        color = if (active) c.bg else c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Text(
                        " · $size",
                        color = if (active) c.bg else c.textMute,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "MP4 includes a generated waveform card with the show art.",
            color = c.textMute,
            fontSize = 12.sp,
        )
    }
}
