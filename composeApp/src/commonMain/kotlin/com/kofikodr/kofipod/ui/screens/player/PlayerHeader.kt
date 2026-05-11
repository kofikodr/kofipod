// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun PlayerHeader(
    episodeNumber: Int?,
    durationMs: Long,
    title: String,
    podcastTitle: String,
    centered: Boolean = true,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    titleLineHeight: androidx.compose.ui.unit.TextUnit = 26.sp,
) {
    val c = LocalKofipodColors.current
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    val horizontalAlignment =
        if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
    ) {
        val kicker = buildKicker(episodeNumber, durationMs)
        if (kicker.isNotEmpty()) {
            Text(
                text = kicker,
                color = c.pink,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                textAlign = textAlign,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = title.ifBlank { "—" },
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = titleFontSize,
            lineHeight = titleLineHeight,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        if (podcastTitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = podcastTitle,
                color = c.textSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun buildKicker(
    episodeNumber: Int?,
    durationMs: Long,
): String {
    val parts = mutableListOf<String>()
    if (episodeNumber != null) parts += "EP · $episodeNumber"
    if (durationMs > 0) parts += "${durationMs / 60_000} MIN"
    return parts.joinToString("  —  ")
}
