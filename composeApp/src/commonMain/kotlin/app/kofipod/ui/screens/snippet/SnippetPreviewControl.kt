// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetPreviewControl(
    isPlaying: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.pink)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(
            name = if (isPlaying) KPIconName.Pause else KPIconName.Play,
            color = c.bg,
            size = 22.dp,
        )
    }
}
