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
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.search.SearchContent
import com.kofikodr.kofipod.ui.screens.search.SearchUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [SearchContent].
 *
 * - Phone (`size == null`): cold-start empty state at 412×892 dp MDPI. Locked in
 *   Task 3.1 as a byte-identical regression guard; **must not drift** when Tasks
 *   3.2 / 3.3 / 3.5 add tablet branches.
 * - Tablet portrait (`size == Tablet8Port` / `Tablet10Port`): cold-start empty
 *   state at 800×1200 / 1000×1400 dp MDPI matching the Task 3.2 mocks.
 *
 * The default `SearchUiState()` resolves through SearchContent's `state.results
 * .isEmpty()` → recs-empty → `EmptyQueryContent.ColdStart` arm, rendering the hero
 * card + popular-categories chips (empty list is fine — the chrome above the
 * categories row is the load-bearing snapshot surface for this guard).
 */
class SearchScreenSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun searchColdStart_phone_light() =
        paparazzi.snapshot {
            SearchHarness(state = SearchUiState(), size = null)
        }

    @Test
    fun searchColdStart_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot {
            SearchHarness(state = SearchUiState(), size = TabletSize.Tablet8Port)
        }
    }

    @Test
    fun searchColdStart_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot {
            SearchHarness(state = SearchUiState(), size = TabletSize.Tablet10Port)
        }
    }

    private fun useTabletDeviceConfig(
        width: Int,
        height: Int,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = width,
                    screenHeight = height,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
        )
    }
}

@Composable
private fun SearchHarness(
    state: SearchUiState,
    size: com.kofikodr.kofipod.ui.layout.TabletSize?,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            SearchContent(
                state = state,
                toastText = null,
                onToastDone = {},
                onQueryChange = {},
                onTabSelect = {},
                onReshuffle = {},
                onLoadMore = {},
                onPickTopic = {},
                onOpenPodcast = {},
                size = size,
            )
        }
    }
}
