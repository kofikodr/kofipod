// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.flow.StateFlow

/**
 * Real-audio visualizer: renders log-spaced FFT bin magnitudes produced by the platform
 * layer (see `KofipodAudioAnalyzer` on Android). The composable is a pure consumer — it
 * performs no synthesis, so when audio is silent or the platform has no analyzer wired
 * (e.g. iOS), bars stay flat.
 *
 * [isPlaying] only drives an envelope fade so transitions in/out of playback look smooth;
 * analysis itself is gated at the platform layer.
 */
@Composable
internal fun PlayerVisualizer(
    isPlaying: Boolean,
    levelsFlow: StateFlow<FloatArray>,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val c = LocalKofipodColors.current
    val levels by levelsFlow.collectAsState()
    val envelope = remember { Animatable(if (isPlaying) 1f else 0f) }
    LaunchedEffect(isPlaying) {
        envelope.animateTo(
            targetValue = if (isPlaying) 1f else 0f,
            animationSpec = tween(durationMillis = if (isPlaying) 250 else 500),
        )
    }

    val brush = Brush.verticalGradient(listOf(c.purple, c.pink))

    Canvas(modifier.fillMaxWidth().height(height)) {
        val count = levels.size
        if (count < 2) return@Canvas
        val totalGap = size.width * 0.38f
        val barWidth = (size.width - totalGap) / count
        val gap = totalGap / (count - 1)
        val centerY = size.height / 2f
        val minHalf = 1.5.dp.toPx()
        val maxHalf = centerY - 1.dp.toPx()
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        val env = envelope.value

        for (i in 0 until count) {
            val level = levels[i].coerceIn(0f, 1f)
            val half = minHalf + (maxHalf - minHalf) * level * env
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, centerY - half),
                size = Size(barWidth, half * 2f),
                cornerRadius = corner,
            )
        }
    }
}
