// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.ui.layout.EmptyDetailHint
import com.kofikodr.kofipod.ui.layout.MasterDetailPane
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for the Phase 1 §7 [MasterDetailPane] primitive.
 *
 * Two snapshots at 10"L (1400×1000): with a selection (master + detail panes both
 * rendering content) and without (master + EmptyDetailHint). Locks the layout
 * topology — 62/38 split, hairline divider, centered empty hint — so later phases
 * (2.4 Search, 6 Settings, 8 Podcast detail) inherit a stable foundation.
 *
 * Content uses solid color blocks rather than real screen bodies so this suite
 * stays decoupled from feature-screen changes.
 */
class MasterDetailPaneSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1400,
                    screenHeight = 1000,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun masterDetail_withSelection_tablet10Land_light() =
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Light) {
                MasterDetailPane(
                    master = { LabelledBox(label = "Master", bg = Color(0xFFFFE0B2)) },
                    detail = { LabelledBox(label = "Detail", bg = Color(0xFFC8E6C9)) },
                    hasSelection = true,
                )
            }
        }

    @Test
    fun masterDetail_emptyDetail_tablet10Land_light() =
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Light) {
                MasterDetailPane(
                    master = { LabelledBox(label = "Master", bg = Color(0xFFFFE0B2)) },
                    detail = {},
                    hasSelection = false,
                    emptyDetail = { EmptyDetailHint(text = "Pick a subscription to preview") },
                )
            }
        }
}

@Composable
private fun LabelledBox(
    label: String,
    bg: Color,
) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = c.text,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
        )
    }
}
