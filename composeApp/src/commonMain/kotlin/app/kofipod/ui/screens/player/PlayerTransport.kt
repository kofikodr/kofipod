// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun PlayerTransport(
    isPlaying: Boolean,
    skipBackSec: Int,
    skipForwardSec: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = KPIconName.PrevTrack,
            size = 28.dp,
            enabled = hasPrev,
            onClick = onPrev,
        )
        SkipButton(
            label = "-${skipBackSec}s",
            onClick = onSkipBack,
        )
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(c.pink)
                .clickable { onTogglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = if (isPlaying) KPIconName.Pause else KPIconName.Play,
                color = Color.White,
                size = 32.dp,
            )
        }
        SkipButton(
            label = "+${skipForwardSec}s",
            onClick = onSkipForward,
        )
        PlayerIconButton(
            icon = KPIconName.NextTrack,
            size = 28.dp,
            enabled = hasNext,
            onClick = onNext,
        )
    }
}

@Composable
private fun PlayerIconButton(
    icon: KPIconName,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val tint = if (enabled) c.text else c.textMute.copy(alpha = 0.4f)
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = icon, color = tint, size = size)
    }
}

@Composable
private fun SkipButton(
    label: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(64.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        )
    }
}
