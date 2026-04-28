// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.kofipod.ai.AiError
import app.kofipod.ai.AiSourceKind
import app.kofipod.ai.AiSummary
import app.kofipod.ai.AiSummaryUiState
import app.kofipod.ai.GeminiModel
import app.kofipod.ai.MentionedLink
import app.kofipod.ai.MentionedPerson
import app.kofipod.ai.MentionedThing
import app.kofipod.ui.screens.detail.ai.MentionedTabContent
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Visual baselines for the Mentioned tab. We snapshot the stateless
 * [MentionedTabContent] so the tests don't need Koin or a repository.
 *
 *  - populated: Ready state with people/things/links — pins the section
 *    headers, name + subtitle layout, and the All filter chip selection.
 *  - emptyMentions: Ready but the entity arrays came back empty — pins the
 *    "No mentions detected" hint copy.
 *  - awaiting: Idle/Generating/Error all share the "open Summary first" hint
 *    card; Idle is enough to pin the visual contract.
 */
class MentionedTabContentSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun mentioned_populated_light() = paparazzi.snapshot { Themed(KofipodThemeMode.Light) { MentionedTabContent(populated()) } }

    @Test
    fun mentioned_populated_dark() = paparazzi.snapshot { Themed(KofipodThemeMode.Dark) { MentionedTabContent(populated()) } }

    @Test
    fun mentioned_emptyMentions_light() = paparazzi.snapshot { Themed(KofipodThemeMode.Light) { MentionedTabContent(emptyMentions()) } }

    @Test
    fun mentioned_awaiting_light() = paparazzi.snapshot { Themed(KofipodThemeMode.Light) { MentionedTabContent(awaiting()) } }
}

@Composable
private fun Themed(
    mode: KofipodThemeMode,
    content: @Composable () -> Unit,
) {
    KofipodTheme(mode) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(LocalKofipodColors.current.bg)
                .padding(16.dp),
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// Sample state
// ---------------------------------------------------------------------------

private fun populated(): AiSummaryUiState =
    AiSummaryUiState.Ready(
        summary =
            AiSummary(
                episodeId = "ep-204",
                generatedAtMs = 1_700_000_000_000L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript,
                sourceFingerprint = "https://example.com/ep-204.vtt",
                summary = "Sample summary prose.",
                people =
                    listOf(
                        MentionedPerson("Mira Halverson", "Host"),
                        MentionedPerson("Wren Acosta", "Guest · Modular"),
                        MentionedPerson("Toby Lin", "Guest · independent"),
                        MentionedPerson("Bret Victor", "Referenced"),
                    ),
                things =
                    listOf(
                        MentionedThing("Learnable Programming", "Essay · 2012"),
                        MentionedThing("Roc", "Language"),
                        MentionedThing("SQLite test suite", "Software"),
                        MentionedThing("Compiler error messages", ""),
                        MentionedThing("Modular", "Company"),
                    ),
                links =
                    listOf(
                        MentionedLink("Learnable Programming", "https://worrydream.com/LearnableProgramming/"),
                        MentionedLink("Roc lang docs", "https://www.roc-lang.org"),
                    ),
            ),
        stale = false,
    )

private fun emptyMentions(): AiSummaryUiState =
    AiSummaryUiState.Ready(
        summary =
            AiSummary(
                episodeId = "ep-empty",
                generatedAtMs = 1_700_000_000_000L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript,
                sourceFingerprint = "https://example.com/ep-empty.vtt",
                summary = "Summary without entities.",
                people = emptyList(),
                things = emptyList(),
                links = emptyList(),
            ),
        stale = false,
    )

private fun awaiting(): AiSummaryUiState = AiSummaryUiState.Error(AiError.RateLimited)
