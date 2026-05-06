// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetTrimChips(
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(label = "IN", value = SnippetWindow.formatTimestampDeci(startMs))
        Pill(label = "OUT", value = SnippetWindow.formatTimestampDeci(endMs))
        Spacer(Modifier.width(0.dp))
        Pill(label = "", value = SnippetWindow.formatTimestampDeci(endMs - startMs) + " selected", filled = true)
    }
}

@Composable
private fun Pill(
    label: String,
    value: String,
    filled: Boolean = false,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (filled) c.pink else c.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                color = if (filled) c.bg else c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            value,
            color = if (filled) c.bg else c.text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
