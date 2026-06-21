// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SearchContentForYouRoutingInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun forYouRecommendationTapUsesSearchResultRoutingCallback() {
        val routedIds = mutableListOf<String>()
        val fullNavigationIds = mutableListOf<String>()
        compose.setContent {
            KofipodTheme(KofipodThemeMode.Light) {
                SearchContent(
                    state =
                        SearchUiState(
                            recommendations = listOf(recommendation(id = "for-you-123", title = "For You Show")),
                        ),
                    toastText = null,
                    onToastDone = {},
                    onQueryChange = {},
                    onTabSelect = {},
                    onReshuffle = {},
                    onLoadMore = {},
                    onPickTopic = {},
                    onOpenPodcast = { fullNavigationIds += it },
                    size = TabletSize.Tablet10Land,
                    onResultTap = { routedIds += it },
                )
            }
        }

        compose.onNodeWithText("For You Show").performClick()

        assertEquals(listOf("for-you-123"), routedIds)
        assertEquals(emptyList(), fullNavigationIds)
    }

    private fun recommendation(
        id: String,
        title: String,
    ): PodcastSummary =
        PodcastSummary(
            id = id,
            feedId = 123L,
            title = title,
            author = "Recommendation Author",
            description = "A recommendation that should use result routing.",
            artworkUrl = "",
            feedUrl = "https://example.test/$id.xml",
            category = "Technology",
        )
}
