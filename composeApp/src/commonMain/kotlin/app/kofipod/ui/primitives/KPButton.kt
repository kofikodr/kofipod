// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
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
import app.kofipod.ui.theme.LocalKofipodRadii

enum class KPButtonStyle { PrimaryPink, SecondaryPurple, Outline }

@Composable
fun KPButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KPButtonStyle = KPButtonStyle.PrimaryPink,
) {
    val colors = LocalKofipodColors.current
    val radii = LocalKofipodRadii.current
    val (bg, fg) = when (style) {
        KPButtonStyle.PrimaryPink -> colors.pink to Color.White
        KPButtonStyle.SecondaryPurple -> colors.purple to Color.White
        KPButtonStyle.Outline -> colors.surface to colors.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radii.pill))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
