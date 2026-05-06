// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun PlayerTopBar(
    podcastTitle: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onGoToPodcast: () -> Unit,
    onMarkPlayed: () -> Unit,
) {
    val c = LocalKofipodColors.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopRoundButton(icon = KPIconName.ChevronDown, onClick = onBack)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "NOW PLAYING",
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = podcastTitle.ifBlank { "—" },
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.size(8.dp))
        Box {
            TopRoundButton(icon = KPIconName.More, onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Share episode") },
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Go to podcast") },
                    onClick = {
                        menuOpen = false
                        onGoToPodcast()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Mark as played") },
                    onClick = {
                        menuOpen = false
                        onMarkPlayed()
                    },
                )
            }
        }
    }
}

@Composable
private fun TopRoundButton(
    icon: KPIconName,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(c.surface)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = icon, color = c.text, size = 20.dp)
    }
}
