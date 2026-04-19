// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.delay

@Composable
internal fun PlayerToast(
    text: String,
    onDone: () -> Unit,
) {
    val c = LocalKofipodColors.current
    LaunchedEffect(text) {
        delay(1600)
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 48.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.purple)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
