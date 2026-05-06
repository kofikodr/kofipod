// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Dismissible NEW coachmark banner shown below [PlayerProActionsRow].
 *
 * Visible only when the user has not yet dismissed it (i.e. [visible] is true).
 * Dismissal writes the current epoch-ms to [SettingsRepository.proTipDismissedAt] via
 * [onDismiss]; subsequent compositions receive [visible] = false and return early.
 */
@Composable
internal fun PlayerProTipBanner(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val c = LocalKofipodColors.current

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pink "+" avatar
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(c.pink),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = c.bg,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.width(10.dp))

        // Label + body text
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "NEW",
                color = c.pink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Tap Snip to clip this moment, Bookmark to save it.",
                color = c.text,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.width(8.dp))

        // Dismiss button
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Close, color = c.textMute, size = 16.dp)
        }
    }
}
