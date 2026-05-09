// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.pro.ProEntitlement
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Single horizontal chip strip below [PlayerTransport]: Snip · Bookmark · Speed · Sleep.
 *
 * Matches the design's unified action row (kofipod-pro-ui-design.html). All four
 * chips share the icon-on-top + label-below shape. The Pro chips (Snip, Bookmark)
 * carry a "PRO" badge for Free / Unknown entitlement; the badge is suppressed for
 * Pro users.
 */
@Composable
internal fun PlayerActionStrip(
    entitlement: ProEntitlement,
    speed: Float,
    sleepRemainingMs: Long?,
    onSnipTapped: () -> Unit,
    onBookmarkTapped: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSetSleep: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showProBadge = entitlement !is ProEntitlement.Pro
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        ChipColumn(
            icon = KPIconName.Scissors,
            label = "Snip",
            showProBadge = showProBadge,
            onClick = onSnipTapped,
        )
        ChipColumn(
            icon = KPIconName.Bookmark,
            label = "Bookmark",
            showProBadge = showProBadge,
            onClick = onBookmarkTapped,
        )
        ChipColumn(
            icon = KPIconName.SpeedUp,
            label = "${formatSpeed(speed)}×",
            showProBadge = false,
            onClick = onCycleSpeed,
        )
        SleepChipColumn(
            sleepRemainingMs = sleepRemainingMs,
            onSetSleep = onSetSleep,
        )
    }
}

@Composable
private fun ChipColumn(
    icon: KPIconName,
    label: String,
    showProBadge: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(c.surface)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = icon, color = c.text, size = 24.dp)
            if (showProBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.pink)
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "PRO",
                        color = c.bg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 10.sp,
                    )
                }
            }
        }
        Text(
            text = label,
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SleepChipColumn(
    sleepRemainingMs: Long?,
    onSetSleep: (Int?) -> Unit,
) {
    val c = LocalKofipodColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(c.surface)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Moon, color = c.text, size = 24.dp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(5, 15, 30, 60).forEach { m ->
                    DropdownMenuItem(
                        text = { Text("$m minutes") },
                        onClick = {
                            menuOpen = false
                            onSetSleep(m)
                        },
                    )
                }
                if (sleepRemainingMs != null) {
                    DropdownMenuItem(
                        text = { Text("Cancel") },
                        onClick = {
                            menuOpen = false
                            onSetSleep(null)
                        },
                    )
                }
            }
        }
        Text(
            text = sleepRemainingMs?.let { formatMs(it) } ?: "Off",
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
