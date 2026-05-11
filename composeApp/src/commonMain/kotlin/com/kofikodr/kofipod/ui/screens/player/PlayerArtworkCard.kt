// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.primitives.KofipodArtwork
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerArtworkCard(
    seed: Int,
    imageUrl: String,
    podcastTitle: String,
    episodeNumber: Int?,
    isPlaying: Boolean,
    audioLevels: StateFlow<FloatArray>,
    artworkMaxWidth: Dp? = null,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Phone default (artworkMaxWidth == null): preserve byte-identical 50% width.
        // Tablet: take the available width up to the cap so artwork scales sensibly
        // on wider devices without ever exceeding the per-size design budget.
        // Note: widthIn must come BEFORE fillMaxWidth — fillMaxWidth honors the
        // incoming max-width constraint that widthIn has just tightened.
        val sizingModifier =
            if (artworkMaxWidth == null) {
                Modifier.fillMaxWidth(0.5f)
            } else {
                Modifier.widthIn(max = artworkMaxWidth).fillMaxWidth()
            }
        Box(
            sizingModifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(r.xl)),
        ) {
            KofipodArtwork(
                seed = seed,
                label = podcastTitle.take(2).uppercase().ifBlank { "" },
                labelSize = 36.dp,
                radius = r.xl,
                model = imageUrl.ifBlank { null },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            // Bottom strip: a dark vertical gradient for bar legibility on any artwork,
            // with the 36dp visualizer sitting against the bottom inside it. The 56dp
            // gradient is intentionally taller than the bars so the fade starts above
            // them — the bars never appear to float on a hard line. The episode badge
            // (rendered last) sits on top of the bars in the BottomEnd corner with its
            // own solid background and stays legible.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
            // Horizontal padding only — vertical padding here would fight the Canvas's
            // own .height(36.dp) and clip the bars. Bottom inset comes from the gradient
            // box being 56dp while the bars are 36dp (20dp natural gap above the bars).
            PlayerVisualizer(
                isPlaying = isPlaying,
                levelsFlow = audioLevels,
                height = 36.dp,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
            )
            if (episodeNumber != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(r.pill))
                        .background(c.pink)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "EP · $episodeNumber",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
