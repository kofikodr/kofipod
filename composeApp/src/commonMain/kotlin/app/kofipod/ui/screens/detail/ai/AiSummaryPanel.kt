// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ai.AiError
import app.kofipod.ai.AiSourceKind
import app.kofipod.ai.AiSummary
import app.kofipod.ai.AiSummaryUiState
import app.kofipod.ai.GeminiModel
import app.kofipod.ai.GenerationStage
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.screens.detail.formatMb
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Clock
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Summary tab body. Obtains its [AiSummaryViewModel] keyed on [episodeId], so
 * navigating between episodes gets a fresh ViewModel scope. The repository
 * behind it is a Koin singleton — the work survives navigation regardless.
 *
 * Layout invariant: every state renders inside the same rounded card frame so
 * tab-content height changes don't reflow the rest of the screen as state
 * transitions through Idle → Generating → Ready.
 */
@Composable
fun AiSummaryPanel(
    episodeId: String,
    audioMinutes: Int,
    onOpenAiSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiSummaryViewModel = koinViewModel(parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    AiSummaryPanelContent(
        state = state,
        audioMinutes = audioMinutes,
        onGenerate = viewModel::onGenerate,
        onCancel = viewModel::onCancel,
        onOpenAiSetup = onOpenAiSetup,
        modifier = modifier,
    )
}

/**
 * Stateless rendering of the panel. Lifted out so Paparazzi snapshots can drive
 * each branch with hand-rolled state, mirroring [AiSummaryPanel]'s shape but
 * without Koin or any VM machinery.
 */
@Composable
internal fun AiSummaryPanelContent(
    state: AiSummaryUiState,
    audioMinutes: Int,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onOpenAiSetup: () -> Unit,
    modifier: Modifier = Modifier,
    // Injectable so Paparazzi snapshots can pin the "X ago" footer to a stable
    // bucket. Production keeps the default — the relative caption updates on
    // every recomposition, which is what the user expects on a long-lived screen.
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
) {
    when (state) {
        AiSummaryUiState.Hidden -> Unit
        is AiSummaryUiState.Idle -> IdleCard(state, audioMinutes, onGenerate, modifier)
        is AiSummaryUiState.Generating -> GeneratingCard(state, onCancel, modifier)
        is AiSummaryUiState.Ready -> ReadyCard(state.summary, state.stale, onGenerate, nowMs, modifier)
        is AiSummaryUiState.Error -> ErrorCard(state.error, onGenerate, onOpenAiSetup, modifier)
    }
}

// -----------------------------------------------------------------------------
// State cards
// -----------------------------------------------------------------------------

/**
 * Idle layout pinned to the v2 mock: an "AI ASSIST" pill (top-left) and an
 * "OPTIONAL" mono tag (top-right) frame the card; the centred dashed
 * Generate button is the focal point; a muted footer below names what the
 * feature actually produces.
 *
 * The footer copy intentionally name-checks Q&A even though the Discuss tab
 * is still a placeholder — telegraphing the v2 surface lets the AI section
 * read as a single feature rather than three loose tabs. Revisit if Q&A
 * gets pushed past the next release window.
 *
 * When no source is available (no transcript, episode not downloaded) the
 * Generate button is suppressed and the footer is swapped to the actionable
 * "Download this episode to summarise its audio." hint so the user has a
 * concrete next step inside the same card chrome.
 */
@Composable
private fun IdleCard(
    state: AiSummaryUiState.Idle,
    audioMinutes: Int,
    onGenerate: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiPillChip(label = "AI ASSIST")
            Spacer(Modifier.weight(1f))
            Text(
                "OPTIONAL",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.available != null) {
            Spacer(Modifier.height(14.dp))
            DashedGenerateButton(
                label = "Generate AI summary",
                onClick = onGenerate,
                modifier = Modifier.testTag("aiPanelIdleGenerateButton"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                when (state.available) {
                    // Source-specific cues survive in the audio-minutes case so
                    // the user sees the upload size implication; transcript path
                    // lands on the unified copy because there's no equivalent
                    // size signal worth pre-disclosing.
                    AiSourceKind.Audio ->
                        "Summary, people, books, links, and Q&A — generated with your key. " +
                            "Uploads ~${audioMinutes}m of audio to Gemini."
                    AiSourceKind.Transcript ->
                        "Summary, people, books, links, and Q&A — all generated locally with your key."
                    // Audio fallback shipped in Slice 2.5 but needs the local file.
                    // Point the user at the Download button (one row above the tab
                    // strip) rather than leaving them on a dead-end card.
                    null -> "Download this episode to summarise its audio."
                },
            color = c.textSoft,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Staged-progress card. Replaces a single "Summarising…" indicator with a
 * three-row checklist that surfaces which step of the generate pipeline is
 * actually running (Preparing → Analysing → Formatting), the upload size for
 * audio runs, plus a Cancel control. Pinned by [GenerationStage] so the
 * repository's stage transitions drive the visible state without the UI
 * having to time anything.
 *
 * Time-remaining estimates are intentionally left out — the only signals we
 * have (file size, average network throughput, model wall-clock) are noisy
 * enough that a wrong "about 2m left" reads worse than no estimate at all.
 */
@Composable
private fun GeneratingCard(
    state: AiSummaryUiState.Generating,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    val labels = stageLabels(state.sourceKind)
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiPillChip(label = "GENERATING…")
            Spacer(Modifier.weight(1f))
            CancelButton(onCancel)
        }
        Spacer(Modifier.height(14.dp))
        StageRow(
            label = labels.preparing,
            indicator = stageIndicator(state.stage, GenerationStage.Preparing),
            // Surfaced only on the upload (Preparing) row, matching the mock.
            // Falls back silently if we don't have a size to show — transcript
            // path leaves the right-hand column empty rather than printing "—".
            trailing = state.sizeBytes?.let { formatMb(it) }.orEmpty(),
        )
        Spacer(Modifier.height(10.dp))
        StageRow(
            label = labels.analysing,
            indicator = stageIndicator(state.stage, GenerationStage.Analysing),
            trailing = "",
        )
        Spacer(Modifier.height(10.dp))
        StageRow(
            label = "Formatting",
            indicator = stageIndicator(state.stage, GenerationStage.Formatting),
            trailing = "",
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "You can leave this screen — we'll keep going in the background.",
            color = c.textSoft,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

private data class StageLabels(
    val preparing: String,
    val analysing: String,
)

private fun stageLabels(sourceKind: AiSourceKind): StageLabels =
    when (sourceKind) {
        AiSourceKind.Audio ->
            StageLabels(
                preparing = "Uploading audio",
                analysing = "Transcribing & analysing",
            )
        AiSourceKind.Transcript ->
            StageLabels(
                preparing = "Fetching transcript",
                analysing = "Analysing",
            )
    }

private enum class StageIndicator { Done, Active, Pending }

private fun stageIndicator(
    current: GenerationStage,
    row: GenerationStage,
): StageIndicator =
    when {
        current.ordinal > row.ordinal -> StageIndicator.Done
        current == row -> StageIndicator.Active
        else -> StageIndicator.Pending
    }

@Composable
private fun StageRow(
    label: String,
    indicator: StageIndicator,
    trailing: String,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StageBullet(indicator)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color =
                    when (indicator) {
                        StageIndicator.Active -> c.text
                        StageIndicator.Done -> c.text
                        StageIndicator.Pending -> c.textMute
                    },
                fontSize = 14.sp,
                fontWeight =
                    if (indicator == StageIndicator.Active) FontWeight.Bold else FontWeight.Medium,
            )
            if (indicator == StageIndicator.Active) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    color = c.pink,
                    trackColor = c.pinkSoft,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .testTag("aiPanelGeneratingProgress"),
                )
            }
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                trailing,
                color = c.textMute,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StageBullet(indicator: StageIndicator) {
    val c = LocalKofipodColors.current
    when (indicator) {
        StageIndicator.Done ->
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.pinkSoft),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Check, color = c.pink, size = 12.dp)
            }
        StageIndicator.Active ->
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.pink),
            )
        StageIndicator.Pending ->
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    // 1.5 dp dashed-effect approximation: a soft outline against
                    // the card background. A true dashed circle needs a custom
                    // PathEffect; not worth the LOC for a tertiary visual cue.
                    .border(1.5.dp, c.border, RoundedCornerShape(999.dp)),
            )
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, c.border, RoundedCornerShape(999.dp))
            // Bare Row + clickable would read as plain text to TalkBack — the
            // role tells assistive tech this is an interactive button. Cancel
            // is the only abort affordance during generation, so its
            // discoverability matters more than the cosmetic regenerate text.
            .semantics { role = Role.Button }
            .clickable { onCancel() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("aiPanelGeneratingCancelButton"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Cancel",
            color = c.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadyCard(
    summary: AiSummary,
    stale: Boolean,
    onRegenerate: () -> Unit,
    nowMs: Long,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        AiPillChip(label = "AI SUMMARY")
        Spacer(Modifier.height(8.dp))
        Text(
            "Summary",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        if (stale) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Source updated — regenerate for the latest version.",
                color = c.warn,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            summary.summary.trim(),
            color = c.text,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.testTag("aiPanelReadySummary"),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${displayName(summary.modelId)} · ${formatRelative(summary.generatedAtMs, nowMs)}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Regenerate",
                color = c.pink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clickable { onRegenerate() }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("aiPanelRegenerateButton"),
            )
        }
    }
}

@Composable
private fun ErrorCard(
    error: AiError,
    onRetry: () -> Unit,
    onOpenAiSetup: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    val presentation = errorPresentation(error)
    PanelCard(modifier = modifier) {
        AiPillChip(label = "AI SUMMARY")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            ErrorIconBadge(name = presentation.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    presentation.headline,
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    presentation.subtitle,
                    color = c.textSoft,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        if (presentation.actionLabel != null) {
            Spacer(Modifier.height(14.dp))
            KPButton(
                label = presentation.actionLabel,
                onClick =
                    when (presentation.action) {
                        ErrorAction.OpenAiSetup -> onOpenAiSetup
                        ErrorAction.Retry, null -> onRetry
                    },
                style = KPButtonStyle.PrimaryPink,
                modifier = Modifier.fillMaxWidth().testTag("aiPanelErrorRetryButton"),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Reusable bits
// -----------------------------------------------------------------------------

@Composable
private fun ErrorIconBadge(name: KPIconName) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.pinkSoft),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = name, color = c.pink, size = 18.dp)
    }
}

// -----------------------------------------------------------------------------
// Error presentation
// -----------------------------------------------------------------------------

private enum class ErrorAction { Retry, OpenAiSetup }

private data class ErrorPresentation(
    val icon: KPIconName,
    val headline: String,
    val subtitle: String,
    val actionLabel: String?,
    val action: ErrorAction?,
)

private fun errorPresentation(error: AiError): ErrorPresentation =
    when (error) {
        AiError.NoKey ->
            ErrorPresentation(
                icon = KPIconName.Pencil,
                headline = "Set up your Gemini key",
                subtitle = "Open Settings → AI features and paste a key to summarise this episode.",
                actionLabel = "Open Settings",
                action = ErrorAction.OpenAiSetup,
            )
        AiError.KeyInvalid ->
            ErrorPresentation(
                icon = KPIconName.Pencil,
                headline = "Your Gemini key was rejected",
                subtitle = "Update it in Settings → AI features.",
                actionLabel = "Open Settings",
                action = ErrorAction.OpenAiSetup,
            )
        AiError.RateLimited ->
            ErrorPresentation(
                icon = KPIconName.Clock,
                headline = "Your Gemini key is rate-limited",
                subtitle = "Try again in a few minutes — Gemini's free tier resets quickly.",
                actionLabel = "Retry",
                action = ErrorAction.Retry,
            )
        AiError.Network ->
            ErrorPresentation(
                icon = KPIconName.Refresh,
                headline = "Couldn't reach Gemini",
                subtitle = "Check your connection and try again.",
                actionLabel = "Retry",
                action = ErrorAction.Retry,
            )
        AiError.TranscriptUnavailable ->
            ErrorPresentation(
                icon = KPIconName.Refresh,
                headline = "Couldn't fetch the transcript",
                subtitle = "The publisher may be having a moment. Try again.",
                actionLabel = "Retry",
                action = ErrorAction.Retry,
            )
        AiError.AudioTooLong ->
            ErrorPresentation(
                icon = KPIconName.Clock,
                headline = "This episode is too long",
                subtitle = "Audio summaries are capped at 8 hours in this version.",
                actionLabel = null,
                action = null,
            )
        is AiError.Unknown ->
            ErrorPresentation(
                icon = KPIconName.Refresh,
                headline = "AI summary failed",
                subtitle = "Tap to retry.",
                actionLabel = "Retry",
                action = ErrorAction.Retry,
            )
    }

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

private fun displayName(modelApiId: String): String = GeminiModel.fromApiId(modelApiId).displayName

private fun formatRelative(
    epochMs: Long,
    nowMs: Long,
): String {
    val deltaSec = (nowMs - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
