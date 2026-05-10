// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kofikodr.kofipod.ai.AiError
import com.kofikodr.kofipod.ai.AiSourceKind
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.ai.AiSummaryUiState
import com.kofikodr.kofipod.ai.GeminiModel
import com.kofikodr.kofipod.ai.GenerationStage
import com.kofikodr.kofipod.ui.screens.detail.ai.AiSummaryPanelContent
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Visual baselines for the Summary tab panel. We snapshot the stateless
 * [AiSummaryPanelContent] so the tests don't depend on Koin or the real
 * repository. Each configuration covers a visually-distinct branch:
 *
 *  - idleTranscript / idleAudio / idleNoSource: the three `available`
 *    branches in [IdleCard]. Pins the subtitle copy variants (transcript
 *    explainer vs audio-with-minutes vs no-source hint) and the
 *    dashed-Generate button presence (Transcript / Audio) vs absence (null).
 *  - generating: the `Summarising…` row + linear progress.
 *  - readyFresh / readyStale: the cached-summary card with and without the
 *    "Source updated" stale chip + Regenerate button.
 *  - errorRateLimited / errorKeyInvalid: two visually-distinct error cards —
 *    different icon, different headline, and different action wiring
 *    (Retry vs Open Settings).
 *  - errorAudioTooLong: the only error variant with NO action button —
 *    pins the "this episode is too long" copy + missing CTA.
 *
 * Per-error copy variants for `Network`/`TranscriptUnavailable`/`Unknown`
 * share the same ErrorCard shell — those variants are pinned by unit tests
 * over the `errorPresentation` mapping rather than by additional baselines.
 */
class AiSummaryPanelSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun aiSummary_idleTranscript_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(idleTranscript()) } }

    @Test
    fun aiSummary_idleTranscript_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(idleTranscript()) } }

    @Test
    fun aiSummary_idleAudio_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(idleAudio()) } }

    @Test
    fun aiSummary_idleAudio_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(idleAudio()) } }

    @Test
    fun aiSummary_idleNoSource_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(idleNoSource()) } }

    @Test
    fun aiSummary_idleNoSource_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(idleNoSource()) } }

    @Test
    fun aiSummary_generating_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(generating()) } }

    @Test
    fun aiSummary_generating_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(generating()) } }

    @Test
    fun aiSummary_generatingTranscriptPreparing_light() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(generatingTranscriptPreparing()) } }

    @Test
    fun aiSummary_generatingTranscriptPreparing_dark() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(generatingTranscriptPreparing()) } }

    @Test
    fun aiSummary_generatingAudioFormatting_light() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(generatingAudioFormatting()) } }

    @Test
    fun aiSummary_generatingAudioFormatting_dark() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(generatingAudioFormatting()) } }

    @Test
    fun aiSummary_generatingAudioPreparing_light() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(generatingAudioPreparing()) } }

    @Test
    fun aiSummary_generatingAudioPreparing_dark() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(generatingAudioPreparing()) } }

    // Mid-chunked-upload state: live byte count + determinate bar. Without
    // this baseline a regression that drops the byte-level progress wire
    // (e.g. the panel ignores Generating.uploadedBytes) would slip past the
    // existing Preparing snapshot, because that fixture has uploadedBytes
    // = null and falls back to the indeterminate bar.
    @Test
    fun aiSummary_generatingAudioUploading_light() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(generatingAudioUploading()) } }

    @Test
    fun aiSummary_generatingAudioUploading_dark() =
        paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(generatingAudioUploading()) } }

    @Test
    fun aiSummary_readyFresh_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(readyFresh()) } }

    @Test
    fun aiSummary_readyFresh_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(readyFresh()) } }

    @Test
    fun aiSummary_readyStale_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(readyStale()) } }

    @Test
    fun aiSummary_readyStale_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(readyStale()) } }

    @Test
    fun aiSummary_errorRateLimited_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(errorRateLimited()) } }

    @Test
    fun aiSummary_errorRateLimited_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(errorRateLimited()) } }

    @Test
    fun aiSummary_errorKeyInvalid_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(errorKeyInvalid()) } }

    @Test
    fun aiSummary_errorKeyInvalid_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(errorKeyInvalid()) } }

    @Test
    fun aiSummary_errorAudioTooLong_light() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Light) { Render(errorAudioTooLong()) } }

    @Test
    fun aiSummary_errorAudioTooLong_dark() = paparazzi.snapshot { ThemedPanel(KofipodThemeMode.Dark) { Render(errorAudioTooLong()) } }
}

@Composable
private fun Render(state: AiSummaryUiState) {
    AiSummaryPanelContent(
        state = state,
        audioMinutes = SAMPLE_AUDIO_MINUTES,
        onGenerate = {},
        onCancel = {},
        onOpenAiSetup = {},
        // Pin the relative-time clock so the Ready card always renders
        // "5m ago" — without this the snapshot drifts every minute (and
        // every day across the d/h boundary), breaking CI verifyPaparazzi.
        nowMs = SAMPLE_GENERATED_AT_MS + 5 * 60 * 1000,
    )
}

@Composable
private fun ThemedPanel(
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
// Sample state — kept inline so the fixtures travel with the test, mirroring
// the EpisodeDetailSnapshots convention.
// ---------------------------------------------------------------------------

private fun idleTranscript(): AiSummaryUiState = AiSummaryUiState.Idle(AiSourceKind.Transcript)

private fun idleAudio(): AiSummaryUiState = AiSummaryUiState.Idle(AiSourceKind.Audio)

private fun idleNoSource(): AiSummaryUiState = AiSummaryUiState.Idle(available = null)

// Audio + Analysing: matches the canonical mock — chip top-left, Cancel
// top-right, "Uploading audio" done with size, "Transcribing & analysing"
// active, "Formatting" pending. Pinning this branch is what catches drift
// against the design once the multi-stage card is in place.
private fun generating(): AiSummaryUiState =
    AiSummaryUiState.Generating(
        sourceKind = AiSourceKind.Audio,
        stage = GenerationStage.Analysing,
        sizeBytes = 58L * 1024 * 1024,
    )

private fun generatingTranscriptPreparing(): AiSummaryUiState =
    AiSummaryUiState.Generating(
        sourceKind = AiSourceKind.Transcript,
        stage = GenerationStage.Preparing,
        sizeBytes = null,
    )

private fun generatingAudioFormatting(): AiSummaryUiState =
    AiSummaryUiState.Generating(
        sourceKind = AiSourceKind.Audio,
        stage = GenerationStage.Formatting,
        sizeBytes = 58L * 1024 * 1024,
    )

// Pins the only stage where the upload-size chip is visible alongside an
// active first-row bullet. Without this fixture, a regression that suppresses
// the size column at Preparing stage only would slip past the Analysing /
// Formatting baselines.
private fun generatingAudioPreparing(): AiSummaryUiState =
    AiSummaryUiState.Generating(
        sourceKind = AiSourceKind.Audio,
        stage = GenerationStage.Preparing,
        sizeBytes = 58L * 1024 * 1024,
    )

// Mid-upload variant — chunked uploader has confirmed ~40% of the payload.
// Pins the determinate progress bar + "X / Y MB" label that appears only
// when both sizeBytes and uploadedBytes are non-null. 23 MB / 58 MB chosen
// so the visible numerator differs from the total; equal numbers would
// render correctly without the byte plumbing actually working.
private fun generatingAudioUploading(): AiSummaryUiState =
    AiSummaryUiState.Generating(
        sourceKind = AiSourceKind.Audio,
        stage = GenerationStage.Preparing,
        sizeBytes = 58L * 1024 * 1024,
        uploadedBytes = 23L * 1024 * 1024,
    )

private fun readyFresh(): AiSummaryUiState = AiSummaryUiState.Ready(sampleSummary(), stale = false)

private fun readyStale(): AiSummaryUiState = AiSummaryUiState.Ready(sampleSummary(), stale = true)

private fun errorRateLimited(): AiSummaryUiState = AiSummaryUiState.Error(AiError.RateLimited)

private fun errorKeyInvalid(): AiSummaryUiState = AiSummaryUiState.Error(AiError.KeyInvalid)

private fun errorAudioTooLong(): AiSummaryUiState = AiSummaryUiState.Error(AiError.AudioTooLong)

private fun sampleSummary(): AiSummary =
    AiSummary(
        episodeId = "ep-204",
        // Pinned timestamp so the "Generated …" caption doesn't drift run-to-run.
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
                "type inference, made visible. The closing segment turns to tooling: how IDE " +
                "integration changed the calculus, why most error messages are read in editors " +
                "now rather than terminals, and what that means for design.",
    )

private const val SAMPLE_AUDIO_MINUTES = 67
private const val SAMPLE_GENERATED_AT_MS = 1_700_000_000_000L // 2023-11-14
