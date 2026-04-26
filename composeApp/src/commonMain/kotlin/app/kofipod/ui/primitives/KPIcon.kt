// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class KPIconName {
    Library,
    Search,
    Downloads,
    Settings,
    Play,
    Pause,
    Download,
    Check,
    Bell,
    Back,
    Plus,
    Close,
    Folder,
    Clock,
    Share,
    More,
    ChevronRight,
    ChevronDown,
    Radar,
    Trash,
    SkipForward,
    SkipBack,
    PrevTrack,
    NextTrack,
    Moon,
    SpeedUp,
    Pencil,
    Chart,
    CoffeeCup,
}

@Composable
fun KPIcon(
    name: KPIconName,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Float = 1.8f,
) {
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val path = remember(name, sizePx) { buildKPIconPath(name, sizePx / 24f) }
    val stroke =
        remember(strokeWidth, sizePx) {
            Stroke(
                width = strokeWidth * (sizePx / 24f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
        }
    val filled = isFilledIcon(name)
    Canvas(modifier = modifier.size(size)) {
        if (filled) {
            drawPath(path = path, color = color)
        } else {
            drawPath(path = path, color = color, style = stroke)
        }
    }
}

private fun isFilledIcon(name: KPIconName): Boolean =
    name == KPIconName.Play ||
        name == KPIconName.Pause ||
        name == KPIconName.PrevTrack ||
        name == KPIconName.NextTrack ||
        name == KPIconName.SpeedUp ||
        name == KPIconName.Moon ||
        name == KPIconName.Chart

private fun buildKPIconPath(
    name: KPIconName,
    scale: Float,
): Path {
    return when (name) {
        KPIconName.Library ->
            Path().apply {
                // Three book stacks
                addRect(Rect(4f * scale, 4f * scale, 8f * scale, 20f * scale))
                addRect(Rect(10f * scale, 4f * scale, 14f * scale, 20f * scale))
                moveTo(16f * scale, 6f * scale)
                lineTo(20f * scale, 7f * scale)
                lineTo(17f * scale, 21f * scale)
                lineTo(13f * scale, 20f * scale)
                close()
            }
        KPIconName.Search ->
            Path().apply {
                addOval(Rect(4f * scale, 4f * scale, 18f * scale, 18f * scale))
                moveTo(20f * scale, 20f * scale)
                lineTo(16.5f * scale, 16.5f * scale)
            }
        KPIconName.Downloads, KPIconName.Download ->
            Path().apply {
                moveTo(12f * scale, 3f * scale)
                lineTo(12f * scale, 16f * scale)
                moveTo(12f * scale, 16f * scale)
                lineTo(8f * scale, 12f * scale)
                moveTo(12f * scale, 16f * scale)
                lineTo(16f * scale, 12f * scale)
                moveTo(4f * scale, 20f * scale)
                lineTo(20f * scale, 20f * scale)
            }
        KPIconName.Settings ->
            Path().apply {
                // Three horizontal slider tracks with offset handles.
                moveTo(3f * scale, 6f * scale)
                lineTo(21f * scale, 6f * scale)
                addOval(Rect(14f * scale, 4f * scale, 18f * scale, 8f * scale))

                moveTo(3f * scale, 12f * scale)
                lineTo(21f * scale, 12f * scale)
                addOval(Rect(7f * scale, 10f * scale, 11f * scale, 14f * scale))

                moveTo(3f * scale, 18f * scale)
                lineTo(21f * scale, 18f * scale)
                addOval(Rect(12f * scale, 16f * scale, 16f * scale, 20f * scale))
            }
        KPIconName.Play ->
            Path().apply {
                moveTo(7f * scale, 4f * scale)
                lineTo(7f * scale, 20f * scale)
                lineTo(20f * scale, 12f * scale)
                close()
            }
        KPIconName.Pause ->
            Path().apply {
                addRect(Rect(6f * scale, 5f * scale, 10f * scale, 19f * scale))
                addRect(Rect(14f * scale, 5f * scale, 18f * scale, 19f * scale))
            }
        KPIconName.Check ->
            Path().apply {
                moveTo(5f * scale, 12f * scale)
                lineTo(10f * scale, 17f * scale)
                lineTo(20f * scale, 7f * scale)
            }
        KPIconName.Bell ->
            Path().apply {
                moveTo(6f * scale, 8f * scale)
                // arc a=6
                cubicTo(6f * scale, 4f * scale, 18f * scale, 4f * scale, 18f * scale, 8f * scale)
                lineTo(18f * scale, 13f * scale)
                lineTo(20f * scale, 16f * scale)
                lineTo(4f * scale, 16f * scale)
                lineTo(6f * scale, 13f * scale)
                close()
                moveTo(10f * scale, 19f * scale)
                cubicTo(10f * scale, 21f * scale, 14f * scale, 21f * scale, 14f * scale, 19f * scale)
            }
        KPIconName.Back ->
            Path().apply {
                moveTo(15f * scale, 6f * scale)
                lineTo(9f * scale, 12f * scale)
                lineTo(15f * scale, 18f * scale)
            }
        KPIconName.Plus ->
            Path().apply {
                moveTo(12f * scale, 5f * scale)
                lineTo(12f * scale, 19f * scale)
                moveTo(5f * scale, 12f * scale)
                lineTo(19f * scale, 12f * scale)
            }
        KPIconName.Close ->
            Path().apply {
                moveTo(6f * scale, 6f * scale)
                lineTo(18f * scale, 18f * scale)
                moveTo(18f * scale, 6f * scale)
                lineTo(6f * scale, 18f * scale)
            }
        KPIconName.Folder ->
            Path().apply {
                moveTo(3f * scale, 7f * scale)
                cubicTo(3f * scale, 6f * scale, 4f * scale, 5f * scale, 5f * scale, 5f * scale)
                lineTo(9f * scale, 5f * scale)
                lineTo(11f * scale, 7f * scale)
                lineTo(19f * scale, 7f * scale)
                cubicTo(20f * scale, 7f * scale, 21f * scale, 8f * scale, 21f * scale, 9f * scale)
                lineTo(21f * scale, 18f * scale)
                cubicTo(21f * scale, 19f * scale, 20f * scale, 20f * scale, 19f * scale, 20f * scale)
                lineTo(5f * scale, 20f * scale)
                cubicTo(4f * scale, 20f * scale, 3f * scale, 19f * scale, 3f * scale, 18f * scale)
                close()
            }
        KPIconName.Clock ->
            Path().apply {
                addOval(Rect(3f * scale, 3f * scale, 21f * scale, 21f * scale))
                moveTo(12f * scale, 7f * scale)
                lineTo(12f * scale, 12f * scale)
                lineTo(15f * scale, 14f * scale)
            }
        KPIconName.Share ->
            Path().apply {
                addOval(Rect(15f * scale, 2f * scale, 21f * scale, 8f * scale))
                addOval(Rect(3f * scale, 9f * scale, 9f * scale, 15f * scale))
                addOval(Rect(15f * scale, 16f * scale, 21f * scale, 22f * scale))
                moveTo(8.6f * scale, 10.5f * scale)
                lineTo(15.4f * scale, 6.5f * scale)
                moveTo(8.6f * scale, 13.5f * scale)
                lineTo(15.4f * scale, 17.5f * scale)
            }
        KPIconName.More ->
            Path().apply {
                addOval(Rect(3.5f * scale, 10.5f * scale, 6.5f * scale, 13.5f * scale))
                addOval(Rect(10.5f * scale, 10.5f * scale, 13.5f * scale, 13.5f * scale))
                addOval(Rect(17.5f * scale, 10.5f * scale, 20.5f * scale, 13.5f * scale))
            }
        KPIconName.ChevronRight ->
            Path().apply {
                moveTo(9f * scale, 6f * scale)
                lineTo(15f * scale, 12f * scale)
                lineTo(9f * scale, 18f * scale)
            }
        KPIconName.ChevronDown ->
            Path().apply {
                moveTo(6f * scale, 9f * scale)
                lineTo(12f * scale, 15f * scale)
                lineTo(18f * scale, 9f * scale)
            }
        KPIconName.Radar ->
            Path().apply {
                addOval(Rect(3f * scale, 3f * scale, 21f * scale, 21f * scale))
                addOval(Rect(7f * scale, 7f * scale, 17f * scale, 17f * scale))
                addOval(Rect(10.5f * scale, 10.5f * scale, 13.5f * scale, 13.5f * scale))
            }
        KPIconName.Trash ->
            Path().apply {
                moveTo(4f * scale, 7f * scale)
                lineTo(20f * scale, 7f * scale)
                moveTo(9f * scale, 7f * scale)
                lineTo(9f * scale, 4f * scale)
                lineTo(15f * scale, 4f * scale)
                lineTo(15f * scale, 7f * scale)
                moveTo(6f * scale, 7f * scale)
                lineTo(7f * scale, 20f * scale)
                lineTo(17f * scale, 20f * scale)
                lineTo(18f * scale, 7f * scale)
                moveTo(10f * scale, 11f * scale)
                lineTo(10f * scale, 17f * scale)
                moveTo(14f * scale, 11f * scale)
                lineTo(14f * scale, 17f * scale)
            }
        KPIconName.SkipForward ->
            Path().apply {
                // Circular arrow with "+" suggestion: ‹arc› + arrowhead on top
                addArc(
                    Rect(4f * scale, 4f * scale, 20f * scale, 20f * scale),
                    startAngleDegrees = -60f,
                    sweepAngleDegrees = 300f,
                )
                moveTo(15f * scale, 2.5f * scale)
                lineTo(18f * scale, 5.5f * scale)
                lineTo(14.5f * scale, 7.5f * scale)
            }
        KPIconName.SkipBack ->
            Path().apply {
                addArc(
                    Rect(4f * scale, 4f * scale, 20f * scale, 20f * scale),
                    startAngleDegrees = 240f,
                    sweepAngleDegrees = -300f,
                )
                moveTo(9f * scale, 2.5f * scale)
                lineTo(6f * scale, 5.5f * scale)
                lineTo(9.5f * scale, 7.5f * scale)
            }
        KPIconName.PrevTrack ->
            Path().apply {
                // Vertical bar on the left + triangle pointing left
                addRect(Rect(5f * scale, 5f * scale, 8f * scale, 19f * scale))
                moveTo(20f * scale, 5f * scale)
                lineTo(10f * scale, 12f * scale)
                lineTo(20f * scale, 19f * scale)
                close()
            }
        KPIconName.NextTrack ->
            Path().apply {
                // Triangle pointing right + vertical bar on the right
                moveTo(4f * scale, 5f * scale)
                lineTo(14f * scale, 12f * scale)
                lineTo(4f * scale, 19f * scale)
                close()
                addRect(Rect(16f * scale, 5f * scale, 19f * scale, 19f * scale))
            }
        KPIconName.Moon ->
            Path().apply {
                // Crescent moon via two overlapping arcs
                moveTo(18f * scale, 14.5f * scale)
                cubicTo(
                    15.5f * scale,
                    16f * scale,
                    11.5f * scale,
                    15.5f * scale,
                    9.5f * scale,
                    13.5f * scale,
                )
                cubicTo(
                    7f * scale,
                    11f * scale,
                    7.5f * scale,
                    7f * scale,
                    10f * scale,
                    5f * scale,
                )
                cubicTo(
                    6f * scale,
                    6f * scale,
                    4f * scale,
                    10f * scale,
                    5f * scale,
                    14f * scale,
                )
                cubicTo(
                    6f * scale,
                    18f * scale,
                    11f * scale,
                    20f * scale,
                    15f * scale,
                    19f * scale,
                )
                cubicTo(
                    16.5f * scale,
                    18.5f * scale,
                    17.5f * scale,
                    16.8f * scale,
                    18f * scale,
                    14.5f * scale,
                )
                close()
            }
        KPIconName.SpeedUp ->
            Path().apply {
                // Lightning bolt
                moveTo(13f * scale, 3f * scale)
                lineTo(5f * scale, 13f * scale)
                lineTo(11f * scale, 13f * scale)
                lineTo(10f * scale, 21f * scale)
                lineTo(19f * scale, 10f * scale)
                lineTo(13f * scale, 10f * scale)
                close()
            }
        KPIconName.Pencil ->
            Path().apply {
                // Tip at bottom-left, shaft diagonal to top-right, eraser/cap at top
                moveTo(4f * scale, 20f * scale)
                lineTo(8f * scale, 19f * scale)
                lineTo(20f * scale, 7f * scale)
                lineTo(17f * scale, 4f * scale)
                lineTo(5f * scale, 16f * scale)
                close()
                moveTo(14f * scale, 7f * scale)
                lineTo(17f * scale, 10f * scale)
            }
        KPIconName.Chart ->
            Path().apply {
                // Three filled bars rising from a baseline, evoking the Stats screen.
                addRect(Rect(4f * scale, 13f * scale, 8f * scale, 20f * scale))
                addRect(Rect(10f * scale, 8f * scale, 14f * scale, 20f * scale))
                addRect(Rect(16f * scale, 11f * scale, 20f * scale, 20f * scale))
            }
        KPIconName.CoffeeCup ->
            Path().apply {
                // Cup body: rounded rect with handle on the right and three steam wisps on top.
                moveTo(5f * scale, 9f * scale)
                lineTo(5f * scale, 17f * scale)
                cubicTo(5f * scale, 19f * scale, 7f * scale, 21f * scale, 9f * scale, 21f * scale)
                lineTo(13f * scale, 21f * scale)
                cubicTo(15f * scale, 21f * scale, 17f * scale, 19f * scale, 17f * scale, 17f * scale)
                lineTo(17f * scale, 9f * scale)
                close()
                // Handle
                moveTo(17f * scale, 11f * scale)
                cubicTo(20.5f * scale, 11f * scale, 20.5f * scale, 16f * scale, 17f * scale, 16f * scale)
                // Steam wisps
                moveTo(8f * scale, 6f * scale)
                cubicTo(7f * scale, 5f * scale, 9f * scale, 4f * scale, 8f * scale, 3f * scale)
                moveTo(11f * scale, 6f * scale)
                cubicTo(10f * scale, 5f * scale, 12f * scale, 4f * scale, 11f * scale, 3f * scale)
                moveTo(14f * scale, 6f * scale)
                cubicTo(13f * scale, 5f * scale, 15f * scale, 4f * scale, 14f * scale, 3f * scale)
            }
    }
}
