// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.primitives

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * The Kofipod brand mark: a soft tile with a centered waveform.
 *
 * Geometry matches the launcher icon (`ic_launcher_foreground.xml`) — 108-unit viewport,
 * composition centered on (54, 54). Caller supplies the canvas size via [modifier].
 */
@Composable
fun KofipodMark(modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    val purple = c.purple
    val pink = c.pink

    Canvas(modifier = modifier) {
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        val s = side / 108f

        fun x(v: Float) = origin.x + v * s

        fun y(v: Float) = origin.y + v * s

        drawRoundRect(
            color = Color(0xFFF7F1FF),
            topLeft = Offset(x(15.188f), y(11.391f)),
            size = Size(77.625f * s, 79.312f * s),
            cornerRadius = CornerRadius(17.297f * s, 17.297f * s),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.42f),
            topLeft = Offset(x(15.188f), y(11.391f)),
            size = Size(77.625f * s, 79.312f * s),
            cornerRadius = CornerRadius(17.297f * s, 17.297f * s),
        )

        fun bar(
            centerX: Float,
            y1: Float,
            y2: Float,
            width: Float,
            color: Color = purple,
        ) {
            drawLine(
                color = color,
                start = Offset(x(centerX), y(y1)),
                end = Offset(x(centerX), y(y2)),
                strokeWidth = width * s,
                cap = StrokeCap.Round,
            )
        }

        bar(29.215f, 48.199f, 59.801f, 4.852f)
        bar(37.547f, 42.188f, 65.813f, 5.063f)
        bar(46.09f, 34.277f, 73.723f, 5.273f)
        bar(54f, 28.688f, 79.313f, 5.063f, pink)
        bar(61.91f, 34.277f, 73.723f, 5.273f)
        bar(70.453f, 42.188f, 65.813f, 5.063f)
        bar(78.785f, 48.199f, 59.801f, 4.852f)
    }
}
