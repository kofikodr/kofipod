// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val haptic = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var armed by remember { mutableStateOf(false) }

    val effectiveFraction =
        dragFraction ?: run {
            if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        }
    val bufferedFraction = bufferedFraction(durationMs, bufferedMs, isLocalSource)

    // Animate visual emphasis between idle (0f) and armed (1f) states.
    val emphasis by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "scrubber-emphasis",
    )
    // Drive the hint label off the animated value so the string flip is in lockstep with
    // the visual state — avoids a one-frame "Scrubbing" / muted-color desync on disarm.
    val showArmedHint = emphasis > 0.5f

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerInput(durationMs) {
                        // Long-press to arm; drag the same finger to scrub; release to commit.
                        // Quick taps and short swipes are ignored so the surrounding column
                        // can scroll without accidentally seeking.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            armed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragFraction = (longPress.position.x / size.width).coerceIn(0f, 1f)
                            longPress.consume()
                            try {
                                drag(longPress.id) { change ->
                                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                    change.consume()
                                }
                                val f = dragFraction
                                if (f != null && durationMs > 0) onSeek((f * durationMs).toLong())
                            } finally {
                                armed = false
                                dragFraction = null
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                    val centerY = size.height / 2f
                    val baseThickness = 3.dp.toPx()
                    val baseRadius = baseThickness / 2f
                    // Fill thickness grows from 6dp (idle) → 8dp (armed).
                    val fillThickness = (6.dp.toPx()) + (2.dp.toPx() * emphasis)
                    val fillRadius = fillThickness / 2f
                    // Track base (thin unbuffered)
                    drawRoundRect(
                        color = c.surfaceAlt,
                        topLeft = Offset(0f, centerY - baseRadius),
                        size = Size(size.width, baseThickness),
                        cornerRadius = CornerRadius(baseRadius, baseRadius),
                    )
                    // Buffered track (thicker, translucent pink) — indicates cached audio ahead of playhead.
                    val bufferedWidth = size.width * bufferedFraction
                    if (bufferedWidth > 0) {
                        drawRoundRect(
                            color = c.pink.copy(alpha = 0.22f + 0.10f * emphasis),
                            topLeft = Offset(0f, centerY - fillRadius),
                            size = Size(bufferedWidth, fillThickness),
                            cornerRadius = CornerRadius(fillRadius, fillRadius),
                        )
                    }
                    // Filled part (purple -> pink gradient), same thickness as buffered.
                    val filledWidth = size.width * effectiveFraction
                    if (filledWidth > 0) {
                        // Idle softens the gradient against the surface; armed shows full vibrancy.
                        val startColor = lerp(lerp(c.purple, c.surface, 0.25f), c.purple, emphasis)
                        val endColor = lerp(lerp(c.pink, c.surface, 0.15f), c.pink, emphasis)
                        drawRoundRect(
                            brush =
                                Brush.horizontalGradient(
                                    colors = listOf(startColor, endColor),
                                    startX = 0f,
                                    endX = size.width,
                                ),
                            topLeft = Offset(0f, centerY - fillRadius),
                            size = Size(filledWidth, fillThickness),
                            cornerRadius = CornerRadius(fillRadius, fillRadius),
                        )
                    }
                    val thumbX = size.width * effectiveFraction
                    // Halo only appears once armed — fades + grows from 6dp → 18dp.
                    val haloRadius = (6.dp.toPx()) + (12.dp.toPx() * emphasis)
                    val haloAlpha = 0.30f * emphasis
                    if (haloAlpha > 0f) {
                        drawCircle(
                            color = c.pink.copy(alpha = haloAlpha),
                            radius = haloRadius,
                            center = Offset(thumbX, centerY),
                        )
                    }
                    // Thumb grows from 5dp (idle) → 8dp (armed).
                    val thumbOuter = (5.dp.toPx()) + (3.dp.toPx() * emphasis)
                    val thumbInner = (3.dp.toPx()) + (2.dp.toPx() * emphasis)
                    drawCircle(
                        color = c.pink,
                        radius = thumbOuter,
                        center = Offset(thumbX, centerY),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawCircle(
                        color = c.surface,
                        radius = thumbInner,
                        center = Offset(thumbX, centerY),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Idle: muted time labels with a subtle "Hold to scrub" hint in the middle.
            // Armed: brighter, bolder time labels and the hint becomes "Scrubbing" in pink.
            val labelColor = lerp(c.textSoft, c.pink, emphasis)
            Text(
                text = formatMs(if (armed && dragFraction != null) (dragFraction!! * durationMs).toLong() else positionMs),
                color = labelColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (showArmedHint) "Scrubbing" else "Hold to scrub",
                color = lerp(c.textSoft.copy(alpha = 0.55f), c.pink, emphasis),
                fontSize = 11.sp,
                fontWeight = if (showArmedHint) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Spacer(Modifier.weight(1f))
            val shownPos = if (armed && dragFraction != null) (dragFraction!! * durationMs).toLong() else positionMs
            val remaining = (durationMs - shownPos).coerceAtLeast(0)
            Text(
                text = "-${formatMs(remaining)}",
                color = labelColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
