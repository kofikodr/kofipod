// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

@Composable
fun KPCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(r.md))
                .background(c.surface)
                .border(BorderStroke(1.dp, c.border), RoundedCornerShape(r.md))
                .padding(16.dp),
    ) { content() }
}
