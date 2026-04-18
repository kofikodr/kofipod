// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import kotlin.math.sin

@Composable
internal fun PlayerBottomBar(
    speed: Float,
    isPlaying: Boolean,
    sleepRemainingMs: Long?,
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
            isPlaying = isPlaying,
            onCycle = onCycleSpeed,
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
private fun SpeedPanel(
    speed: Float,
    isPlaying: Boolean,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        modifier
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
        Spacer(Modifier.width(12.dp))
        VuBars(active = isPlaying, modifier = Modifier.width(48.dp).height(24.dp))
    }
}

@Composable
private fun VuBars(active: Boolean, modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    val transition = rememberInfiniteTransition(label = "vu")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900)),
        label = "phase",
    )
    val calm by animateFloatAsState(targetValue = if (active) 1f else 0.2f, label = "calm")
    Canvas(modifier) {
        val bars = 5
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val offset = i.toFloat() / bars
            val osc = if (active) {
                0.5f + 0.5f * sin((phase + offset) * 2f * kotlin.math.PI.toFloat() + i)
            } else {
                0.25f
            }
            val h = size.height * (0.25f + 0.75f * osc) * calm
            val x = i * (barWidth + gap)
            val y = size.height - h
            drawRoundRect(
                color = c.pink,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
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
                    onClick = { menuOpen = false; onSetSleep(m) },
                )
            }
            if (remainingMs != null) {
                DropdownMenuItem(
                    text = { Text("Cancel") },
                    onClick = { menuOpen = false; onSetSleep(null) },
                )
            }
        }
    }
}

