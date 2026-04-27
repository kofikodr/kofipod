// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ai.AiError
import app.kofipod.ai.AiSourceKind
import app.kofipod.ai.AiSummary
import app.kofipod.ai.AiSummaryUiState
import app.kofipod.ai.GeminiModel
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
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
    onOpenAiSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AiSummaryUiState.Hidden -> Unit
        is AiSummaryUiState.Idle -> IdleCard(state, audioMinutes, onGenerate, modifier)
        is AiSummaryUiState.Generating -> GeneratingCard(modifier)
        is AiSummaryUiState.Ready -> ReadyCard(state.summary, state.stale, onGenerate, modifier)
        is AiSummaryUiState.Error -> ErrorCard(state.error, onGenerate, onOpenAiSetup, modifier)
    }
}

// -----------------------------------------------------------------------------
// State cards
// -----------------------------------------------------------------------------

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
            SparkleBadge()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Generate AI Insights for this episode",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (state.available) {
                        AiSourceKind.Transcript -> "Uses your Gemini key. Reads the published transcript."
                        AiSourceKind.Audio -> "Uses your Gemini key. ~${audioMinutes}m of audio."
                        null -> "This episode has no transcript yet. Audio summary coming in a future update."
                    },
                    color = c.textSoft,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        if (state.available != null) {
            Spacer(Modifier.height(14.dp))
            DashedGenerateButton(
                label = "Generate AI summary",
                onClick = onGenerate,
                modifier = Modifier.testTag("aiPanelIdleGenerateButton"),
            )
        }
    }
}

@Composable
private fun GeneratingCard(modifier: Modifier) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SparkleBadge()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Summarising…",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = c.pink,
                    trackColor = c.pinkSoft,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .testTag("aiPanelGeneratingProgress"),
                )
            }
        }
    }
}

@Composable
private fun ReadyCard(
    summary: AiSummary,
    stale: Boolean,
    onRegenerate: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        EyebrowChip()
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
                "${displayName(summary.modelId)} · ${formatRelative(summary.generatedAtMs)}",
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
        EyebrowChip()
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
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun SparkleBadge() {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.pinkSoft),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 18.dp)
    }
}

@Composable
private fun EyebrowChip() {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.pinkSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 11.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            "AI SUMMARY",
            color = c.pink,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

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

@Composable
private fun DashedGenerateButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    // Solid border in v1 — Compose lacks first-class dashed strokes for borders
    // and a stable PathEffect-based implementation is more code than the visual
    // delta is worth right now. Tracked under "later polish".
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .border(width = 1.5.dp, color = c.pink, shape = RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = c.pink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
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

private fun formatRelative(epochMs: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val deltaSec = (now - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
