// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.kofipod.snippets.WaveformSamples
import app.kofipod.ui.theme.LocalKofipodColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Audio waveform with overlaid IN/OUT trim handles + a vertical preview
 * scrubber.
 *
 * The view is *zoomed* to a viewport `[viewportStartMs, viewportEndMs]`
 * computed by the caller as the trim window plus a contextual margin. Without
 * this zoom a 60-second snip in a 60-minute episode would render as a 1.6%
 * sliver — handles unreachable, playhead motion imperceptible. With the zoom
 * the trim occupies most of the waveform, handles are well-separated, and a
 * second of playback advances the playhead by a clearly visible delta.
 *
 * Faded bars sit outside the trim window; saturated pink bars sit inside it.
 * Pink handle chips with a small grip indicator sit at startMs / endMs.
 * Playhead is rendered last so it always reads above the bars.
 *
 * Drag-to-trim: only the IN/OUT handle chips themselves are grabbable —
 * touches outside a [HANDLE_HIT_RADIUS_DP] zone around either handle are
 * ignored (no "middle of the waveform" drag, which previously snapped the
 * closer handle under the finger). The chosen handle is honoured for the
 * rest of the gesture, and the viewport is frozen for the duration so the
 * un-dragged handle and the bar field don't visually drift while the trim's
 * auto-zoom would otherwise rescale mid-drag.
 */
@Composable
fun SnippetWaveform(
    samples: WaveformSamples,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    viewportStartMs: Long,
    viewportEndMs: Long,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val widthPxState = remember { mutableStateOf(0f) }
    // A single DragSession ties the picked handle and the frozen viewport
    // together atomically — they have to coexist or both be null, never one
    // without the other. Without this, the cancel/end paths and onDragStart
    // would each have to clear two states symmetrically, and any future
    // edit that forgot one would silently corrupt the freeze invariant.
    val dragSessionState = remember { mutableStateOf<DragSession?>(null) }

    // `pointerInput` is keyed on Unit, so without these "live" wrappers the
    // gesture closure would capture stale start/end/viewport values across
    // drags — the user could never grab a handle that had moved (the End
    // handle in particular, whose pixel position shifts every time Start
    // moves and the viewport rescales around the new trim).
    val startMsState = rememberUpdatedState(startMs)
    val endMsState = rememberUpdatedState(endMs)
    val viewportStartState = rememberUpdatedState(viewportStartMs)
    val viewportEndState = rememberUpdatedState(viewportEndMs)

    val handleWidthPx = with(LocalDensity.current) { 8.dp.toPx() }
    val hitRadiusPx = with(LocalDensity.current) { HANDLE_HIT_RADIUS_DP.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT_DP.dp)
            .onSizeChanged { widthPxState.value = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = widthPxState.value
                        val vStart = viewportStartState.value
                        val vEnd = viewportEndState.value
                        val hit =
                            resolveHandleHit(
                                touchXPx = offset.x,
                                startMs = startMsState.value,
                                endMs = endMsState.value,
                                viewportStartMs = vStart,
                                viewportEndMs = vEnd,
                                widthPx = w,
                                hitRadiusPx = hitRadiusPx,
                            )
                        dragSessionState.value =
                            if (hit != null) DragSession(hit, vStart, vEnd) else null
                    },
                    onDragEnd = { dragSessionState.value = null },
                    onDragCancel = { dragSessionState.value = null },
                    onDrag = { change, _ ->
                        val session = dragSessionState.value ?: return@detectDragGestures
                        val w = widthPxState.value
                        val vSpan = session.frozenViewportEndMs - session.frozenViewportStartMs
                        if (w <= 0f || vSpan <= 0L) return@detectDragGestures
                        val ratio = (change.position.x / w).coerceIn(0f, 1f)
                        val tappedMs = session.frozenViewportStartMs + (ratio * vSpan).toLong()
                        when (session.handle) {
                            SnippetHandle.Start -> onStartChanged(tappedMs.coerceAtMost(endMsState.value - MIN_WINDOW_MS))
                            SnippetHandle.End -> onEndChanged(tappedMs.coerceAtLeast(startMsState.value + MIN_WINDOW_MS))
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(WAVEFORM_HEIGHT_DP.dp)) {
            // Read the frozen session inside the draw lambda so the snapshot
            // observer is registered on the draw phase. That closes the
            // one-frame race where onDragStart could write the freeze before
            // the next recomposition delivered new captured locals — bars
            // and the un-dragged handle would briefly draw against the live
            // (already-rescaled) viewport even though onDrag's math was
            // already pinned.
            val session = dragSessionState.value
            val drawViewportStart = session?.frozenViewportStartMs ?: viewportStartMs
            val drawViewportEnd = session?.frozenViewportEndMs ?: viewportEndMs
            drawWaveform(
                bars = samples.bars,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                playheadMs = playheadMs,
                viewportStartMs = drawViewportStart,
                viewportEndMs = drawViewportEnd,
                handleWidthPx = handleWidthPx,
                pinkColor = c.pink,
                pinkSoftColor = c.pinkSoft,
                playheadColor = c.text,
                handleColor = c.pink,
                handleGripColor = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Pure handle-hit resolver, lifted out of the pointer-input block so it can
 * be unit-tested without driving Compose's gesture machinery.
 *
 * Returns the closer handle if [touchXPx] is within [hitRadiusPx] of either
 * handle's centre in pixel space; null otherwise. Ties (touch equidistant
 * from both, e.g. handles at minimum span) resolve to [SnippetHandle.Start]
 * — arbitrary but stable, and the user can always grab the End handle via a
 * fresh tap nearer to it.
 *
 * Degenerate inputs ([widthPx] ≤ 0, viewport with non-positive span) return
 * null rather than dividing by zero.
 */
internal fun resolveHandleHit(
    touchXPx: Float,
    startMs: Long,
    endMs: Long,
    viewportStartMs: Long,
    viewportEndMs: Long,
    widthPx: Float,
    hitRadiusPx: Float,
): SnippetHandle? {
    if (widthPx <= 0f) return null
    val span = viewportEndMs - viewportStartMs
    if (span <= 0L) return null
    val spanF = span.toFloat()
    val startX = ((startMs - viewportStartMs).toFloat() / spanF) * widthPx
    val endX = ((endMs - viewportStartMs).toFloat() / spanF) * widthPx
    val dStart = abs(touchXPx - startX)
    val dEnd = abs(touchXPx - endX)
    return when {
        dStart > hitRadiusPx && dEnd > hitRadiusPx -> null
        dStart <= dEnd -> SnippetHandle.Start
        else -> SnippetHandle.End
    }
}

/**
 * Compute the viewport bounds (in episode-time ms) for the waveform: the trim
 * window expanded by [contextRatio] of its own length on each side, clamped
 * to `[0, durationMs]`. Falls back to the full episode duration if the trim
 * is degenerate.
 */
fun computeWaveformViewport(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    contextRatio: Float = 0.5f,
): Pair<Long, Long> {
    if (durationMs <= 0L || endMs <= startMs) return 0L to durationMs.coerceAtLeast(1L)
    val span = endMs - startMs
    val pad = (span * contextRatio).toLong().coerceAtLeast(MIN_VIEWPORT_PAD_MS)
    val rawStart = startMs - pad
    val rawEnd = endMs + pad
    // If padding overshoots either side, shift the other side outward by the
    // overshoot so the trim still gets the requested context (clamped to
    // [0, duration]). Keeps the trim from sitting flush against an edge when
    // the snip is near the start or end of the episode.
    val overflowLeft = (-rawStart).coerceAtLeast(0L)
    val overflowRight = (rawEnd - durationMs).coerceAtLeast(0L)
    val viewStart = max(0L, rawStart - overflowRight)
    val viewEnd = min(durationMs, rawEnd + overflowLeft)
    return viewStart to viewEnd
}

private fun DrawScope.drawWaveform(
    bars: FloatArray,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    viewportStartMs: Long,
    viewportEndMs: Long,
    handleWidthPx: Float,
    pinkColor: Color,
    pinkSoftColor: Color,
    playheadColor: Color,
    handleColor: Color,
    handleGripColor: Color,
) {
    if (bars.isEmpty() || durationMs <= 0L) return
    val viewSpan = (viewportEndMs - viewportStartMs).coerceAtLeast(1L)
    val w = size.width
    val h = size.height
    val barCount = bars.size
    val barSpacing = w / barCount
    val barWidth = barSpacing * 0.55f
    bars.forEachIndexed { i, v ->
        // Distribute bars uniformly across the viewport — the placeholder
        // generator already produces a fixed bar count not tied to real audio
        // sampling, so stretching them to fill the viewport keeps the
        // waveform visually dense at every zoom level.
        val ratio = (i + 0.5f) / barCount
        val barCenterEpisodeMs = viewportStartMs + (ratio * viewSpan).toLong()
        val inWindow = barCenterEpisodeMs in startMs..endMs
        val x = ratio * w - barWidth / 2f
        val barH = h * v
        val y = (h - barH) / 2f
        drawRoundRect(
            color = if (inWindow) pinkColor else pinkSoftColor,
            topLeft = Offset(x, y),
            size = Size(barWidth, barH),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }

    fun episodeMsToX(ms: Long): Float = ((ms - viewportStartMs).toFloat() / viewSpan.toFloat() * w).coerceIn(0f, w)

    val startX = episodeMsToX(startMs)
    val endX = episodeMsToX(endMs)
    drawHandle(centerX = startX, height = h, widthPx = handleWidthPx, fillColor = handleColor, gripColor = handleGripColor)
    drawHandle(centerX = endX, height = h, widthPx = handleWidthPx, fillColor = handleColor, gripColor = handleGripColor)
    if (playheadMs != null) {
        val phX = episodeMsToX(playheadMs)
        // 3px reads cleanly while moving against the bar field; a 2px line at
        // high pixel densities sub-pixels into the bars.
        drawRect(playheadColor, Offset(phX - 1.5f, 0f), Size(3f, h))
    }
}

private fun DrawScope.drawHandle(
    centerX: Float,
    height: Float,
    widthPx: Float,
    fillColor: Color,
    gripColor: Color,
) {
    val handleH = height * 0.55f
    val top = (height - handleH) / 2f
    val left = centerX - widthPx / 2f
    drawRoundRect(
        color = fillColor,
        topLeft = Offset(left, top),
        size = Size(widthPx, handleH),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
    )
    // Center grip line for affordance.
    val gripH = handleH * 0.4f
    val gripTop = top + (handleH - gripH) / 2f
    val gripW = (widthPx * 0.18f).coerceAtLeast(1.5f)
    drawRect(
        color = gripColor,
        topLeft = Offset(centerX - gripW / 2f, gripTop),
        size = Size(gripW, gripH),
    )
}

private const val WAVEFORM_HEIGHT_DP = 120
private const val MIN_WINDOW_MS = 1_000L
private const val MIN_VIEWPORT_PAD_MS = 2_000L

/**
 * Touch-radius around each handle's pixel centre that counts as a "grab".
 * 24dp ≈ comfortable thumb hit-zone (Material targets are 48dp diameter, i.e.
 * 24dp radius), comfortably wider than the 8dp handle chip itself, and small
 * enough to leave a "no-grab" dead-zone in the middle for any selection wider
 * than ~48dp on screen.
 */
private const val HANDLE_HIT_RADIUS_DP = 24

internal enum class SnippetHandle { Start, End }

/**
 * State of an active drag gesture. Atomic: either we have a picked handle
 * AND a frozen viewport, or both are absent. Holding them together
 * eliminates the invariant burden from the cancel/end paths.
 */
private data class DragSession(
    val handle: SnippetHandle,
    val frozenViewportStartMs: Long,
    val frozenViewportEndMs: Long,
)
