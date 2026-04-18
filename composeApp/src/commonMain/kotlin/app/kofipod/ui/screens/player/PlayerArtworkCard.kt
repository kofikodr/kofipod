// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

@Composable
internal fun PlayerArtworkCard(
    seed: Int,
    imageUrl: String,
    podcastTitle: String,
    episodeNumber: Int?,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
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
