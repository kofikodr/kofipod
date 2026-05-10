// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.shell.KofipodScaffold
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baseline for the tablet branch of [KofipodScaffold]. Phase 1 Task 1.2:
 * verify the Row(rail, Column(content, dockedMiniPlayer)) layout renders at 10" landscape.
 *
 * The phone branch is intentionally not re-baselined here — Task 1.2 explicitly states
 * "phone baseline at 412x892 unchanged". The existing per-screen baselines guard regression.
 */
class KofipodScaffoldSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            // 1400 x 1000 dp at xdpi/ydpi 160 = 1 px per dp.
            // Paparazzi treats screenWidth/screenHeight as the device's native
            // portrait dimensions and swaps them when orientation = LANDSCAPE.
            // So portrait (1000, 1400) rotated lands at 1400 x 1000 dp on screen.
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1000,
                    screenHeight = 1400,
                    xdpi = 160,
                    ydpi = 160,
                    orientation = ScreenOrientation.LANDSCAPE,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun tablet10Land_light() =
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Light) {
                CompositionLocalProvider(LocalTabletSize provides TabletSize.Tablet10Land) {
                    TabletScaffoldHarness()
                }
            }
        }
}

@androidx.compose.runtime.Composable
private fun TabletScaffoldHarness() {
    val nav = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val c = LocalKofipodColors.current
    KofipodScaffold(nav = nav, snackbarHostState = snackbarHostState) {
        Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
            Text(text = "Content here", color = c.text)
        }
    }
}
