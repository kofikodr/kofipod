// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.kofikodr.kofipod.ai.AiSourceKind
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.ai.AiSummaryUiState
import com.kofikodr.kofipod.ai.DiscussCitation
import com.kofikodr.kofipod.ai.DiscussMessage
import com.kofikodr.kofipod.ai.DiscussRole
import com.kofikodr.kofipod.ai.DiscussUiState
import com.kofikodr.kofipod.ai.GeminiModel
import com.kofikodr.kofipod.ai.MentionedPerson
import com.kofikodr.kofipod.ui.layout.LocalTabletSize
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.screens.askgemini.AskGeminiContent
import com.kofikodr.kofipod.ui.screens.askgemini.EpisodeHeader
import com.kofikodr.kofipod.ui.screens.detail.ai.AiSummaryPanelContent
import com.kofikodr.kofipod.ui.screens.detail.ai.DiscussTabPanelContent
import com.kofikodr.kofipod.ui.screens.detail.ai.MentionedTabContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Phase 10 — tablet-aware visual baselines for the AI surfaces.
 *
 * Focused subset (12 baselines) covering the per-size width / grid
 * adaptations introduced in Phase 10:
 *  - Summary (3): phone + Tablet10Land (720 dp cap) + Tablet8Port (no cap).
 *  - Mentioned (4): phone (1 col) + 8L (2 col) + 10P (2 col) + 10L (3 col).
 *  - Discuss (3): phone idle + Tablet10Land idle (720 cap) + Tablet10Land active.
 *  - AskGemini (2): Tablet8Port (640 dp) + Tablet10Land (760 dp).
 *
 * Full state×size matrices (12 + 12 + 8 + 4) are deferred — these 12 prove the
 * adaptation contract per per-size invariant.
 */
class AiInsightsSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            // Default = phone; per-test overrides hop to tablet sizes via unsafeUpdateConfig.
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

    // -----------------------------------------------------------------------
    // Summary tab
    // -----------------------------------------------------------------------

    @Test
    fun aiSummary_ready_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot { SummaryHarness(size = null, state = readyFresh()) }
    }

    @Test
    fun aiSummary_ready_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot { SummaryHarness(size = TabletSize.Tablet10Land, state = readyFresh()) }
    }

    @Test
    fun aiSummary_ready_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot { SummaryHarness(size = TabletSize.Tablet8Port, state = readyFresh()) }
    }

    // -----------------------------------------------------------------------
    // Mentioned tab
    // -----------------------------------------------------------------------

    @Test
    fun mentioned_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot { MentionedHarness(size = null) }
    }

    @Test
    fun mentioned_tablet8Land_light() {
        useLandscapeTabletConfig(width = 1200, height = 800)
        paparazzi.snapshot { MentionedHarness(size = TabletSize.Tablet8Land) }
    }

    @Test
    fun mentioned_tablet10Port_light() {
        useTabletDeviceConfig(width = 1000, height = 1400)
        paparazzi.snapshot { MentionedHarness(size = TabletSize.Tablet10Port) }
    }

    @Test
    fun mentioned_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot { MentionedHarness(size = TabletSize.Tablet10Land) }
    }

    // -----------------------------------------------------------------------
    // Discuss tab
    // -----------------------------------------------------------------------

    @Test
    fun discuss_idle_phone_light() {
        resetToPhoneConfig()
        paparazzi.snapshot { DiscussHarness(size = null, state = discussIdle()) }
    }

    @Test
    fun discuss_idle_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot { DiscussHarness(size = TabletSize.Tablet10Land, state = discussIdle()) }
    }

    @Test
    fun discuss_active_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot { DiscussHarness(size = TabletSize.Tablet10Land, state = discussActive()) }
    }

    // -----------------------------------------------------------------------
    // AskGemini full-screen
    // -----------------------------------------------------------------------

    @Test
    fun askGemini_tablet8Port_light() {
        useTabletDeviceConfig(width = 800, height = 1200)
        paparazzi.snapshot { AskGeminiHarness(size = TabletSize.Tablet8Port, state = askGeminiActive()) }
    }

    @Test
    fun askGemini_tablet10Land_light() {
        useLandscapeTabletConfig(width = 1400, height = 1000)
        paparazzi.snapshot { AskGeminiHarness(size = TabletSize.Tablet10Land, state = askGeminiActive()) }
    }

    // -----------------------------------------------------------------------
    // Config helpers — mirrors EpisodeDetailScreenSnapshots
    // -----------------------------------------------------------------------

    private fun resetToPhoneConfig() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
        )
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

// ---------------------------------------------------------------------------
// Harness composables — wrap the stateless `*Content` seams in theme + bg
// fill + tablet-size composition local so the Phase 10 width-cap / column
// logic kicks in inside the snapshot.
// ---------------------------------------------------------------------------

@Composable
private fun SummaryHarness(
    size: TabletSize?,
    state: AiSummaryUiState,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg)
                    .padding(16.dp),
            ) {
                AiSummaryPanelContent(
                    state = state,
                    audioMinutes = SAMPLE_AUDIO_MINUTES,
                    onGenerate = {},
                    onCancel = {},
                    onOpenAiSetup = {},
                    nowMs = SAMPLE_GENERATED_AT_MS + 5 * 60 * 1000,
                    size = size,
                )
            }
        }
    }
}

@Composable
private fun MentionedHarness(size: TabletSize?) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg)
                    .padding(16.dp),
            ) {
                MentionedTabContent(state = mentionedPopulated(), size = size)
            }
        }
    }
}

@Composable
private fun DiscussHarness(
    size: TabletSize?,
    state: DiscussUiState,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg)
                    .padding(16.dp),
            ) {
                DiscussTabPanelContent(
                    state = state,
                    onOpenAskGemini = {},
                    onClearChat = {},
                    size = size,
                )
            }
        }
    }
}

@Composable
private fun AskGeminiHarness(
    size: TabletSize?,
    state: DiscussUiState,
) {
    KofipodTheme(KofipodThemeMode.Light) {
        CompositionLocalProvider(LocalTabletSize provides size) {
            val c = LocalKofipodColors.current
            Box(Modifier.fillMaxSize().background(c.bg)) {
                AskGeminiContent(
                    state = state,
                    composerText = "",
                    header = EpisodeHeader(title = "Compiler error messages, redesigned", podcastTitle = "Future of Coding"),
                    onBack = {},
                    onComposerChange = {},
                    onSubmit = {},
                    onSubmitPreset = {},
                    onClearChat = {},
                    onRetry = {},
                    onCitationTap = {},
                    size = size,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

private const val SAMPLE_AUDIO_MINUTES = 67
private const val SAMPLE_GENERATED_AT_MS = 1_700_000_000_000L // 2023-11-14

private fun readyFresh(): AiSummaryUiState = AiSummaryUiState.Ready(sampleSummary(), stale = false)

private fun sampleSummary(): AiSummary =
    AiSummary(
        episodeId = "ep-204",
        generatedAtMs = SAMPLE_GENERATED_AT_MS,
        modelId = GeminiModel.Flash.apiId,
        sourceKind = AiSourceKind.Transcript,
        sourceFingerprint = "https://example.com/ep-204.vtt",
        summary =
            "The hosts open with a long arc on compiler error messages — why a 400-line " +
                "stack trace from 2010 felt user-hostile, and how teams gradually figured out " +
                "that pointing at the offending span is worth more than printing the whole " +
                "AST. They walk through Rust's borrow-checker hints as a case study, then " +
                "pivot to what Elm got right early and where its choices showed their seams. " +
                "Mid-episode, a tangent on TypeScript's recent narrowing improvements lands " +
                "on the observation that better diagnostics often start as failed attempts at " +
                "type inference, made visible.",
    )

private fun mentionedPopulated(): AiSummaryUiState =
    AiSummaryUiState.Ready(
        summary =
            AiSummary(
                episodeId = "ep-204",
                generatedAtMs = SAMPLE_GENERATED_AT_MS,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript,
                sourceFingerprint = "https://example.com/ep-204.vtt",
                summary = "Sample summary prose.",
                // 6+ people so 2-col and 3-col grids both have a full first row plus
                // wrap, which is what catches grid-vs-list regressions.
                people =
                    listOf(
                        MentionedPerson("Mira Halverson", "Host"),
                        MentionedPerson("Wren Acosta", "Guest · Modular"),
                        MentionedPerson("Toby Lin", "Guest · independent"),
                        MentionedPerson("Bret Victor", "Referenced"),
                        MentionedPerson("Niko Matsakis", "Rust core"),
                        MentionedPerson("Evan Czaplicki", "Elm creator"),
                    ),
                things = emptyList(),
                links = emptyList(),
            ),
        stale = false,
    )

private fun discussIdle(): DiscussUiState =
    DiscussUiState.Ready(
        messages = emptyList(),
        suggestions =
            listOf(
                "What's the main argument the hosts make about error messages?",
                "Where does the Rust borrow-checker tangent start?",
                "Summarise the closing tooling segment.",
                "What does Elm get right that the hosts call out?",
            ),
        quickPrompts = listOf("Skim mode", "For my notes", "Key disagreements", "TL;DR"),
        inFlight = false,
        error = null,
    )

private fun discussActive(): DiscussUiState =
    DiscussUiState.Ready(
        messages =
            listOf(
                DiscussMessage(
                    id = "1",
                    role = DiscussRole.User,
                    content = "What's the strongest argument the hosts make about compiler diagnostics?",
                    citations = emptyList(),
                    createdAtMs = SAMPLE_GENERATED_AT_MS,
                ),
                DiscussMessage(
                    id = "2",
                    role = DiscussRole.Model,
                    content =
                        "The hosts argue that pointing at the offending span is worth more " +
                            "than dumping a 400-line stack trace. They cite Rust's " +
                            "borrow-checker as the canonical example.",
                    citations =
                        listOf(
                            DiscussCitation(label = "12:34", timestampMs = 754_000),
                            DiscussCitation(label = "18:02", timestampMs = 1_082_000),
                        ),
                    createdAtMs = SAMPLE_GENERATED_AT_MS + 5_000,
                ),
                DiscussMessage(
                    id = "3",
                    role = DiscussRole.User,
                    content = "What do they say about IDE integration changing the calculus?",
                    citations = emptyList(),
                    createdAtMs = SAMPLE_GENERATED_AT_MS + 10_000,
                ),
                DiscussMessage(
                    id = "4",
                    role = DiscussRole.Model,
                    content =
                        "They agree IDE inline diagnostics shift the bar: once the squiggle " +
                            "and the fix-it sit next to the cursor, terse compiler text is " +
                            "fine — the IDE is doing the teaching, not the message itself.",
                    citations =
                        listOf(
                            DiscussCitation(label = "24:18", timestampMs = 1_458_000),
                        ),
                    createdAtMs = SAMPLE_GENERATED_AT_MS + 15_000,
                ),
            ),
        suggestions = emptyList(),
        quickPrompts = emptyList(),
        inFlight = false,
        error = null,
    )

private fun askGeminiActive(): DiscussUiState = discussActive()
