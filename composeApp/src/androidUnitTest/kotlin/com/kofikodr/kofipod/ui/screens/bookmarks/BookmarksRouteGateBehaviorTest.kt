// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.bookmarks

import androidx.compose.material3.Text
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarksRouteGateBehaviorTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 360,
                    screenHeight = 640,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun deniedRouteRequestsPaywallAndLeavesWithoutRenderingProtectedContent() {
        var paywallRequests = 0
        var backs = 0
        var protectedContentCompositions = 0

        paparazzi.snapshot {
            BookmarksRouteGate(
                canEnterRoute = false,
                requestRoutePaywall = { paywallRequests++ },
                onBack = { backs++ },
            ) {
                protectedContentCompositions++
                Text("Protected bookmarks")
            }
        }

        assertEquals(1, paywallRequests)
        assertEquals(1, backs)
        assertEquals(0, protectedContentCompositions)
    }

    @Test
    fun allowedRouteRendersProtectedContentWithoutPaywallOrBackNavigation() {
        var paywallRequests = 0
        var backs = 0
        var protectedContentCompositions = 0

        paparazzi.snapshot {
            BookmarksRouteGate(
                canEnterRoute = true,
                requestRoutePaywall = { paywallRequests++ },
                onBack = { backs++ },
            ) {
                protectedContentCompositions++
                Text("Protected bookmarks")
            }
        }

        assertEquals(0, paywallRequests)
        assertEquals(0, backs)
        assertEquals(1, protectedContentCompositions)
    }
}
