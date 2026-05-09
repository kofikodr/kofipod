// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.SnippetWindow
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Bottom row of the waveform card. Per the Slice 4 design:
 *   [IN 18:42]  [OUT 19:24] ········ [▶ Preview]
 *
 * IN / OUT are read-only timestamp pills (drag the waveform handles to move
 * them). Preview is a lavender-tinted action pill that toggles snippet
 * playback. Pushed to the right edge so the action lives in a predictable
 * spot regardless of trim values.
 */
@Composable
fun SnippetTrimChips(
    startMs: Long,
    endMs: Long,
    isPreviewing: Boolean,
    onPreviewToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimePill(label = "IN", value = SnippetWindow.formatTimestamp(startMs))
        TimePill(label = "OUT", value = SnippetWindow.formatTimestamp(endMs))
        Spacer(Modifier.weight(1f))
        PreviewPill(isPlaying = isPreviewing, onTap = onPreviewToggle)
    }
}

@Composable
private fun TimePill(
    label: String,
    value: String,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.surfaceAlt)
            .border(1.dp, c.border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = c.textMute,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            color = c.text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PreviewPill(
    isPlaying: Boolean,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.purpleTint)
            .border(1.dp, c.border, CircleShape)
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(
            name = if (isPlaying) KPIconName.Pause else KPIconName.Play,
            color = c.purple,
            size = 14.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (isPlaying) "Pause" else "Preview",
            color = c.purple,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
