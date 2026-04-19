// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StarterPackScreen(
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    viewModel: StarterPackViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg)) {
        StarterPackHeader(onBack = onBack, onRefresh = viewModel::refresh)
        when {
            state.loading ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = c.pink)
                }
            state.error != null ->
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(state.error!!, color = c.danger)
                }
            else ->
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.picks, key = { it.id }) { p ->
                        StarterRow(p = p, onClick = { onOpenPodcast(p.id) })
                    }
                }
        }
    }
}

@Composable
private fun StarterPackHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(r.pill))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(c.purpleTint)
                    .clickable { onRefresh() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Reshuffle", color = c.purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Starter pack",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 28.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "A curated dozen, pulled live from Podcast Index trending.",
            color = c.textMute,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StarterRow(
    p: PodcastSummary,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 56.dp,
            seed = p.feedId.toInt(),
            label = p.title,
            radius = 12.dp,
            model = p.artworkUrl.ifBlank { null },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (p.author.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    p.author,
                    color = c.textSoft,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.category.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    p.category.uppercase(),
                    color = c.textMute,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
    }
}
