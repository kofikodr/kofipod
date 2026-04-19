// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import coil3.compose.AsyncImage

/**
 * Gradient + diagonal-stripe art placeholder that matches the design's `KpArt`.
 * Caller sizes via [modifier]. When [model] loads via Coil it covers the placeholder;
 * on failure/blank the gradient + label remain.
 */
@Composable
fun KofipodArtwork(
    seed: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    radius: Dp = 14.dp,
    labelSize: Dp = 14.dp,
    model: Any? = null,
    contentDescription: String? = null,
) {
    val dark = LocalKofipodColors.current.isDark
    val (a, b) = artGradient(seed, dark)
    Box(modifier = modifier.clip(RoundedCornerShape(radius))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(a, b))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val stripeSpacing = 10.dp.toPx()
                    val strokeWidth = 1.dp.toPx()
                    onDrawBehind {
                        val stripeColor = Color.White.copy(alpha = 0.13f)
                        val diag = this.size.width + this.size.height
                        var x = -this.size.height
                        while (x < diag) {
                            drawLine(
                                color = stripeColor,
                                start = Offset(x, 0f),
                                end = Offset(x + this.size.height, this.size.height),
                                strokeWidth = strokeWidth,
                            )
                            x += stripeSpacing
                        }
                    }
                },
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!label.isNullOrBlank() && model == null) {
            Text(
                text = label.take(2).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                fontSize = labelSize.value.sp,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 6.dp),
            )
        }
    }
}

/** Convenience overload for square artwork. */
@Composable
fun KofipodArtwork(
    size: Dp,
    seed: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    radius: Dp = 14.dp,
    model: Any? = null,
    contentDescription: String? = null,
) = KofipodArtwork(
    seed = seed,
    modifier = modifier.size(size),
    label = label,
    radius = radius,
    labelSize = (size.value * 0.13f).coerceAtLeast(9f).dp,
    model = model,
    contentDescription = contentDescription,
)

private fun artGradient(
    seed: Int,
    dark: Boolean,
): Pair<Color, Color> {
    val slot = ((seed * 37) and 0x7FFFFFFF) % GRADIENTS.size
    val entry = GRADIENTS[slot]
    return if (dark) entry.dark else entry.light
}

private data class GradientPair(val light: Pair<Color, Color>, val dark: Pair<Color, Color>)

private val GRADIENTS: List<GradientPair> =
    listOf(
        GradientPair(Color(0xFF6D3BD2) to Color(0xFF8B5CF6), Color(0xFF7C4DEB) to Color(0xFFC4A6FF)),
        GradientPair(Color(0xFF4B1E9E) to Color(0xFF7C3AED), Color(0xFFA881F5) to Color(0xFFC4A6FF)),
        GradientPair(Color(0xFF8B5CF6) to Color(0xFFD946EF), Color(0xFFC4A6FF) to Color(0xFFFF6BB5)),
        GradientPair(Color(0xFF6D3BD2) to Color(0xFF4B1E9E), Color(0xFF7C4DEB) to Color(0xFF4B1E9E)),
        GradientPair(Color(0xFFFF2E9A) to Color(0xFF8B5CF6), Color(0xFFFF6BB5) to Color(0xFFA881F5)),
        GradientPair(Color(0xFF4F46E5) to Color(0xFF8B5CF6), Color(0xFF6366F1) to Color(0xFFA881F5)),
    )
