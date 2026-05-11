// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.kofikodr.kofipod.ui.layout.RailMode
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.shell.RailContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [com.kofikodr.kofipod.ui.shell.KofipodNavigationRail] — one
 * snapshot per [RailMode]. Library is the selected route in every snapshot so the active
 * pill is visible. Renders `RailContent` directly (no NavHostController needed).
 */
class KofipodNavigationRailSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 260,
                    screenHeight = 1000,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun iconOnly_light() =
        paparazzi.snapshot {
            RailHarness(RailMode.IconOnly)
        }

    @Test
    fun iconLabel_light() =
        paparazzi.snapshot {
            RailHarness(RailMode.IconLabel)
        }

    @Test
    fun expanded_light() =
        paparazzi.snapshot {
            RailHarness(RailMode.Expanded)
        }
}

@Composable
private fun RailHarness(mode: RailMode) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            RailContent(
                currentRoute = Route.Library::class.qualifiedName,
                mode = mode,
                onSelect = {},
            )
        }
    }
}
