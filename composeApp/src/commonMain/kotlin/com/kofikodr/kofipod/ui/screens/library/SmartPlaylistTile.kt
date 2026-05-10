// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii

/**
 * Square Library tile rendering a single Smart Playlist.
 *
 * Visual differentiation from [ListTile] is intentional: list tiles use the codebase's
 * sampled-palette purple gradient + folder glyph, while playlist tiles use a pink-tinted
 * gradient with the [KPIconName.Sparkle] glyph so users can tell at a glance which tiles
 * are user-curated vs predicate-driven. The tile mirrors `ListTile`'s chrome
 * (`aspectRatio(1f)`, `clip(RoundedCornerShape(r.md))`, 12dp content padding) so the
 * grid stays visually homogenous.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmartPlaylistTile(
    modifier: Modifier,
    playlist: SmartPlaylist,
    matchedCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val gradient =
        Brush.linearGradient(
            colors = listOf(c.pinkSoft, c.pink, c.purple),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r.md))
            .background(gradient)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        KPIcon(
            name = KPIconName.Sparkle,
            color = Color.White,
            size = 22.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                playlist.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$matchedCount EPISODE${if (matchedCount == 1) "" else "S"}",
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
    }
}
