// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.kofipod.snippets.WaveformSamples
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetWaveform(
    samples: WaveformSamples,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val widthPxState = remember { mutableStateOf(0f) }
    val draggingHandleState = remember { mutableStateOf<DragHandle?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 4.dp)
            .onSizeChanged { widthPxState.value = it.width.toFloat() }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = widthPxState.value
                        if (w <= 0f || durationMs <= 0L) return@detectDragGestures
                        // Latch which handle the user grabbed by initial proximity.
                        // Honour this choice for the rest of the gesture so dragging
                        // past the other handle's position doesn't flip the snap.
                        val ratio = (offset.x / w).coerceIn(0f, 1f)
                        val tappedMs = (ratio * durationMs).toLong()
                        draggingHandleState.value =
                            if (kotlin.math.abs(tappedMs - startMs) <= kotlin.math.abs(tappedMs - endMs)) {
                                DragHandle.Start
                            } else {
                                DragHandle.End
                            }
                    },
                    onDragEnd = { draggingHandleState.value = null },
                    onDragCancel = { draggingHandleState.value = null },
                    onDrag = { change, _ ->
                        val w = widthPxState.value
                        if (w <= 0f || durationMs <= 0L) return@detectDragGestures
                        val ratio = (change.position.x / w).coerceIn(0f, 1f)
                        val tappedMs = (ratio * durationMs).toLong()
                        when (draggingHandleState.value) {
                            DragHandle.Start -> onStartChanged(tappedMs.coerceAtMost(endMs - MIN_WINDOW_MS))
                            DragHandle.End -> onEndChanged(tappedMs.coerceAtLeast(startMs + MIN_WINDOW_MS))
                            null -> { /* gesture not yet started */ }
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            drawBars(
                bars = samples.bars,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                playheadMs = playheadMs,
                pinkColor = c.pink,
                neutralColor = c.surface,
                playheadColor = c.text,
            )
        }
    }
}

private fun DrawScope.drawBars(
    bars: FloatArray,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    pinkColor: Color,
    neutralColor: Color,
    playheadColor: Color,
) {
    if (bars.isEmpty()) return
    val w = size.width
    val h = size.height
    val barCount = bars.size
    val barSpacing = w / barCount
    val barWidth = barSpacing * 0.6f
    bars.forEachIndexed { i, v ->
        val barCenterMs = ((i + 0.5f) / barCount * durationMs).toLong()
        val inWindow = barCenterMs in startMs..endMs
        val barH = h * v
        val x = i * barSpacing + (barSpacing - barWidth) / 2f
        val y = (h - barH) / 2f
        drawRoundRect(
            color = if (inWindow) pinkColor else neutralColor,
            topLeft = Offset(x, y),
            size = Size(barWidth, barH),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
    if (playheadMs != null && durationMs > 0L) {
        val phX = (playheadMs.toFloat() / durationMs * w).coerceIn(0f, w)
        drawRect(playheadColor, Offset(phX - 1f, 0f), Size(2f, h))
    }
}

private const val MIN_WINDOW_MS = 1_000L

private enum class DragHandle { Start, End }
