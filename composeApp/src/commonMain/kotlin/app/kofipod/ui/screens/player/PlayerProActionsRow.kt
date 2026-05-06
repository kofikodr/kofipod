// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Chip row surfacing the Pro-gated Snip and Bookmark actions below [PlayerTransport].
 *
 * Free / Unknown users see a small pink "PRO" pill badge anchored top-right on each chip.
 * Pro users see plain icons — the badge is hidden.
 *
 * Tapping a chip always invokes the callback; the paywall gate is enforced inside
 * [PlayerViewModel.onSnipTapped] and [PlayerViewModel.onBookmarkTapped].
 */
@Composable
internal fun PlayerProActionsRow(
    entitlement: ProEntitlement,
    onSnipTapped: () -> Unit,
    onBookmarkTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showProBadge = entitlement !is ProEntitlement.Pro

    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        ProIconChip(
            icon = KPIconName.Scissors,
            label = "Snip",
            showProBadge = showProBadge,
            onClick = onSnipTapped,
        )
        Spacer(Modifier.width(16.dp))
        ProIconChip(
            icon = KPIconName.Bookmark,
            label = "Bookmark",
            showProBadge = showProBadge,
            onClick = onBookmarkTapped,
        )
    }
}

@Composable
private fun ProIconChip(
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
