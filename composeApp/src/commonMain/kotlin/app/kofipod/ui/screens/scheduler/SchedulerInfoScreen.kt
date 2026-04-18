// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.scheduler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SchedulerInfoScreen(
    onBack: () -> Unit,
    viewModel: SchedulerInfoViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Text(
            "← Back",
            color = c.textSoft,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.height(16.dp))
        Text("Scheduler", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kofipod checks for new episodes about once a day when your device is on Wi-Fi and charging. The exact time depends on battery and network, so it isn't a precise schedule.",
            color = c.textSoft,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            if (state.dailyEnabled) "Enabled" else "Disabled",
            color = if (state.dailyEnabled) c.pink else c.textMute,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "LAST ${state.runs.size} RUN${if (state.runs.size == 1) "" else "S"}",
            color = c.textMute,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (state.runs.isEmpty()) {
            Text(
                "No runs yet. The next run will appear here after your first daily check.",
                color = c.textMute,
                fontSize = 13.sp,
            )
        } else {
            Chart(
                values = state.runs.map { it.inserted },
                purple = c.purple,
                track = c.purpleTint,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "New episodes per run",
                color = c.textMute,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Chart(values: List<Int>, purple: androidx.compose.ui.graphics.Color, track: androidx.compose.ui.graphics.Color) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val barWidth = ((w - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val hBar = (v.toFloat() / max) * h
            val x = i * (barWidth + gap)
            drawRect(color = track, topLeft = Offset(x, 0f), size = Size(barWidth, h))
            drawRect(color = purple, topLeft = Offset(x, h - hBar), size = Size(barWidth, hBar))
        }
    }
}
