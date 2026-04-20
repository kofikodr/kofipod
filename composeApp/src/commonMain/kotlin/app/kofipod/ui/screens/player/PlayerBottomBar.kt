// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerBottomBar(
    speed: Float,
    isPlaying: Boolean,
    sleepRemainingMs: Long?,
    audioLevels: StateFlow<FloatArray>,
    onCycleSpeed: () -> Unit,
    onSetSleep: (Int?) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedPanel(
            speed = speed,
            onCycle = onCycleSpeed,
        )
        Spacer(Modifier.width(12.dp))
        PlayerVisualizer(
            isPlaying = isPlaying,
            levelsFlow = audioLevels,
            height = 40.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        SleepPanel(
            remainingMs = sleepRemainingMs,
            onSetSleep = onSetSleep,
        )
    }
}

@Composable
private fun RowScope.SpeedPanel(
    speed: Float,
    onCycle: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .clip(RoundedCornerShape(r.sm))
            .clickable { onCycle() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(r.sm))
                .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.SpeedUp, color = c.purple, size = 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "SPEED",
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = "${formatSpeed(speed)}×",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun SleepPanel(
    remainingMs: Long?,
    onSetSleep: (Int?) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(r.sm))
            .clickable { menuOpen = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Moon, color = c.text, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = remainingMs?.let { formatMs(it) } ?: "Off",
            color = c.text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(5, 15, 30, 60).forEach { m ->
                DropdownMenuItem(
                    text = { Text("$m minutes") },
                    onClick = {
                        menuOpen = false
                        onSetSleep(m)
                    },
                )
            }
            if (remainingMs != null) {
                DropdownMenuItem(
                    text = { Text("Cancel") },
                    onClick = {
                        menuOpen = false
                        onSetSleep(null)
                    },
                )
            }
        }
    }
}
