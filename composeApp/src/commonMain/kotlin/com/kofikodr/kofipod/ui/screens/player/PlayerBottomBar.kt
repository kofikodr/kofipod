// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.coroutines.flow.StateFlow

/**
 * Slim audio-levels visualizer strip beneath the unified action chips. The free
 * visualisation isn't part of the Pro design but is preserved as existing chrome —
 * speed and sleep are surfaced as chips in [PlayerActionStrip] now.
 */
@Composable
internal fun PlayerVisualizerStrip(
    isPlaying: Boolean,
    audioLevels: StateFlow<FloatArray>,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerVisualizer(
            isPlaying = isPlaying,
            levelsFlow = audioLevels,
            height = 40.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
