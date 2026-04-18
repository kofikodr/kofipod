// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun KPBadge(label: String, modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    Text(
        text = label.uppercase(),
        color = c.pink,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.pinkSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
