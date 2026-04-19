// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors

/** Small pill-shaped chip used for filter tabs, category tags, status flags. */
@Composable
fun KPChip(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    tone: KPChipTone = KPChipTone.Neutral,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalKofipodColors.current
    val (bg, fg, border) =
        when {
            selected && tone == KPChipTone.Pink -> Triple(c.pink, c.surface, c.pink)
            selected -> Triple(c.purple, c.surface, c.purple)
            tone == KPChipTone.Outline -> Triple(Color.Transparent, c.textSoft, c.border)
            else -> Triple(c.purpleTint, c.text, c.purpleTint)
        }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(999.dp))
                .let { if (onClick != null) it.clickable { onClick() } else it }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 12.5.sp,
        )
    }
}

enum class KPChipTone { Neutral, Pink, Outline }
