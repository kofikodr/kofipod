// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

/**
 * Rich podcast row used by Search and Library Detail: 64dp artwork, title, author, description.
 * Pass [onLongClick] to enable long-press (e.g. for a delete confirmation).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PodcastCard(
    title: String,
    author: String,
    description: String,
    seed: Int,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 64.dp,
            seed = seed,
            label = title,
            radius = 12.dp,
            model = artworkUrl?.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank()) {
                Text(
                    author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    color = c.textMute,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
