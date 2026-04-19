// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * The Kofipod brand mark: purple play triangle with a smiling face and pink sound waves.
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
        val s = size.minDimension / 108f

        val triangle =
            Path().apply {
                moveTo(52.75f * s, 35.5f * s)
                cubicTo(
                    71.825f * s,
                    48.45f * s,
                    71.825f * s,
                    59.55f * s,
                    52.75f * s,
                    72.5f * s,
                )
                cubicTo(
                    33.675f * s,
                    85.45f * s,
                    25.5f * s,
                    79.9f * s,
                    25.5f * s,
                    54f * s,
                )
                cubicTo(
                    25.5f * s,
                    28.1f * s,
                    33.675f * s,
                    22.55f * s,
                    52.75f * s,
                    35.5f * s,
                )
                close()
            }
        drawPath(triangle, purple)

        val leftEye =
            Path().apply {
                moveTo(34f * s, 50f * s)
                quadraticBezierTo(37.5f * s, 47f * s, 41f * s, 50f * s)
            }
        drawPath(leftEye, Color.White, style = Stroke(width = 2.8f * s, cap = StrokeCap.Round))

        val rightEye =
            Path().apply {
                moveTo(47f * s, 50f * s)
                quadraticBezierTo(50.5f * s, 47f * s, 54f * s, 50f * s)
            }
        drawPath(rightEye, Color.White, style = Stroke(width = 2.8f * s, cap = StrokeCap.Round))

        val smile =
            Path().apply {
                moveTo(36.5f * s, 56f * s)
                quadraticBezierTo(44f * s, 62f * s, 51.5f * s, 56f * s)
            }
        drawPath(smile, pink, style = Stroke(width = 4.4f * s, cap = StrokeCap.Round))

        val waveCenterX = 68f * s
        val waveCenterY = 54f * s
        listOf(5f, 9f, 13f).forEach { r ->
            val rScaled = r * s
            drawArc(
                color = pink,
                startAngle = -60f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(waveCenterX - rScaled, waveCenterY - rScaled),
                size = Size(2 * rScaled, 2 * rScaled),
                style = Stroke(width = 3.0f * s, cap = StrokeCap.Round),
            )
        }
    }
}
