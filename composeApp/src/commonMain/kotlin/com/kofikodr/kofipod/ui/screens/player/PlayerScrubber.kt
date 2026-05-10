// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Fraction (0..1) of the scrubber to render as "buffered ahead". Returns 1f for local
 * media — the entire file is on disk, so the buffered indicator should show full from
 * the moment playback starts (ExoPlayer's bufferedPosition for progressive sources only
 * reflects what's been pulled into the sample buffer, which lags far behind the actual
 * on-disk availability).
 */
internal fun bufferedFraction(
    durationMs: Long,
    bufferedMs: Long,
    isLocalSource: Boolean,
): Float =
    when {
        isLocalSource -> 1f
        durationMs > 0 -> (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

@Composable
internal fun PlayerScrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    isLocalSource: Boolean = false,
) {
    val c = LocalKofipodColors.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val effectiveFraction =
        dragFraction ?: run {
            if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        }
    val bufferedFraction = bufferedFraction(durationMs, bufferedMs, isLocalSource)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures(
                            onPress = {
                                val f = (it.x / size.width).coerceIn(0f, 1f)
                                dragFraction = f
                                tryAwaitRelease()
                                val commit = dragFraction
                                dragFraction = null
                                if (commit != null && durationMs > 0) {
                                    onSeek((commit * durationMs).toLong())
                                }
                            },
                        )
                    }
                    .pointerInput(durationMs) {
                        detectDragGestures(
                            onDragStart = { off ->
                                dragFraction = (off.x / size.width).coerceIn(0f, 1f)
                            },
                            onDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                val f = dragFraction
                                dragFraction = null
                                if (f != null && durationMs > 0) onSeek((f * durationMs).toLong())
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                    val centerY = size.height / 2f
                    val baseThickness = 3.dp.toPx()
                    val baseRadius = baseThickness / 2f
                    val fillThickness = 6.dp.toPx()
                    val fillRadius = fillThickness / 2f
                    // Track base (thin unbuffered)
                    drawRoundRect(
                        color = c.surfaceAlt,
                        topLeft = Offset(0f, centerY - baseRadius),
                        size = Size(size.width, baseThickness),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(baseRadius, baseRadius),
                    )
                    // Buffered track (thicker, translucent pink) — indicates cached audio ahead of playhead.
                    val bufferedWidth = size.width * bufferedFraction
                    if (bufferedWidth > 0) {
                        drawRoundRect(
                            color = c.pink.copy(alpha = 0.28f),
                            topLeft = Offset(0f, centerY - fillRadius),
                            size = Size(bufferedWidth, fillThickness),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(fillRadius, fillRadius),
                        )
                    }
                    // Filled part (purple -> pink gradient), same thickness as buffered.
                    val filledWidth = size.width * effectiveFraction
                    if (filledWidth > 0) {
                        drawRoundRect(
                            brush =
                                Brush.horizontalGradient(
                                    colors = listOf(c.purple, c.pink),
                                    startX = 0f,
                                    endX = size.width,
                                ),
                            topLeft = Offset(0f, centerY - fillRadius),
                            size = Size(filledWidth, fillThickness),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(fillRadius, fillRadius),
                        )
                    }
                    // Thumb halo (faint pink glow)
                    val thumbX = size.width * effectiveFraction
                    drawCircle(
                        color = c.pink.copy(alpha = 0.22f),
                        radius = 14.dp.toPx(),
                        center = Offset(thumbX, centerY),
                    )
                    // Thumb
                    drawCircle(
                        color = c.pink,
                        radius = 7.dp.toPx(),
                        center = Offset(thumbX, centerY),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawCircle(
                        color = c.surface,
                        radius = 5.dp.toPx(),
                        center = Offset(thumbX, centerY),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatMs(positionMs),
                color = c.textSoft,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            val remaining = (durationMs - positionMs).coerceAtLeast(0)
            Text(
                text = "-${formatMs(remaining)}",
                color = c.textSoft,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
