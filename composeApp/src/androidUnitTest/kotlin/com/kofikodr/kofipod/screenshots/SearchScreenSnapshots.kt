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
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.search.SearchContent
import com.kofikodr.kofipod.ui.screens.search.SearchUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [SearchContent]. The landscape `withSelection` variant
 * deliberately passes no `selectedSearchResultId` to the harness — the embedded
 * detail pane is the real [com.kofikodr.kofipod.ui.screens.detail.PodcastDetailScreen]
 * resolved via Koin at runtime, so Paparazzi (no Koin context) can only baseline the
 * no-selection landscape state. The selection-on path is covered by manual on-device
 * verification + the routing unit test.
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

    @Test
    fun searchEmpty_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            SearchHarness(state = SearchUiState(), size = TabletSize.Tablet8Land)
        }
    }

    @Test
    fun searchEmpty_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            SearchHarness(state = SearchUiState(), size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun searchPopulated_tablet10Land_noSelection_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot {
            SearchHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet10Land)
        }
    }

    @Test
    fun searchPopulated_tablet8Land_noSelection_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot {
            SearchHarness(state = POPULATED_FIXTURE, size = TabletSize.Tablet8Land)
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

    private fun useLandscapeTabletConfig(
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
                    orientation = ScreenOrientation.LANDSCAPE,
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

private fun summary(
    id: String,
    feedId: Long,
    title: String,
    author: String,
    description: String,
    category: String,
    episodeCount: Int,
): PodcastSummary =
    PodcastSummary(
        id = id,
        feedId = feedId,
        title = title,
        author = author,
        description = description,
        artworkUrl = "",
        feedUrl = "https://example.test/$id.xml",
        category = category,
        episodeCount = episodeCount,
    )

private val POPULATED_FIXTURE: SearchUiState by lazy {
    SearchUiState(
        query = "long form interviews",
        results =
            listOf(
                summary(
                    id = "1001",
                    feedId = 1001L,
                    title = "Long Form Interviews",
                    author = "Marigold Studios",
                    description =
                        "Hour-plus conversations with builders, writers, and scientists. " +
                            "Each guest sits down for a wide-ranging chat about craft, taste, " +
                            "and the long arc of getting good at something hard.",
                    category = "Interviews",
                    episodeCount = 184,
                ),
                summary(
                    id = "1002",
                    feedId = 1002L,
                    title = "Slow Conversations",
                    author = "The Library Society",
                    description =
                        "A quiet show about deep work, attention, and the people who " +
                            "practice it. Released monthly. No ads, no urgency.",
                    category = "Society",
                    episodeCount = 42,
                ),
                summary(
                    id = "1003",
                    feedId = 1003L,
                    title = "On The Record",
                    author = "Caraway House",
                    description =
                        "Working journalists explain how they reported the year's most " +
                            "consequential stories — sources, drafts, dead ends and all.",
                    category = "News",
                    episodeCount = 67,
                ),
            ),
    )
}
