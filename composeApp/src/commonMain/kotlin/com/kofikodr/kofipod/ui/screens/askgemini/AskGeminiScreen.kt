// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.askgemini

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ai.AiError
import com.kofikodr.kofipod.ai.DiscussCitation
import com.kofikodr.kofipod.ai.DiscussMessage
import com.kofikodr.kofipod.ai.DiscussProgress
import com.kofikodr.kofipod.ai.DiscussProgressStage
import com.kofikodr.kofipod.ai.DiscussRole
import com.kofikodr.kofipod.ai.DiscussUiState
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.layout.rememberTabletSize
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.screens.detail.ai.AiPillChip
import com.kofikodr.kofipod.ui.screens.detail.ai.SendBubble
import com.kofikodr.kofipod.ui.screens.detail.ai.SuggestionCard
import com.kofikodr.kofipod.ui.screens.detail.formatMbProgress
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Full-screen Ask Gemini chat. Top header strip, scrollable body
 * (suggestions + chips when empty, message thread when active), sticky
 * composer pinned to the bottom with `imePadding()`.
 *
 * The bottom navigation strip stays visible (matches `EpisodeDetail` and
 * `AiSetup`); the `Scaffold` shell in [com.kofikodr.kofipod.ui.shell.AppShell] is
 * what owns the bottom bar, and we deliberately don't try to suppress it
 * here.
 */
@Composable
fun AskGeminiScreen(
    episodeId: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    // `key = episodeId` so the per-episode AskGemini VM is correctly scoped
    // if multiple AskGemini routes for different episodes ever share a
    // ViewModelStore (e.g. tablet adaptation, multi-pane chat). Matches the
    // AI tab keying convention in `AiSummaryPanel` / `MentionedTabPanel` /
    // `DiscussTabPanel`.
    viewModel: AskGeminiViewModel =
        koinViewModel(key = episodeId, parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    val composer by viewModel.composer.collectAsState()
    val header by viewModel.header.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AskGeminiContent(
        state = state,
        composerText = composer,
        header = header,
        onBack = onBack,
        onComposerChange = viewModel::onComposerChange,
        onSubmit = viewModel::submit,
        onSubmitPreset = viewModel::submitPreset,
        onClearChat = viewModel::clearChat,
        onRetry = viewModel::retry,
        onCitationTap = { ms ->
            scope.launch {
                if (viewModel.seekToCitation(ms)) onOpenPlayer()
            }
        },
        size = rememberTabletSize(),
    )
}

@Composable
internal fun AskGeminiContent(
    state: DiscussUiState,
    composerText: String,
    header: EpisodeHeader,
    onBack: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitPreset: (String) -> Unit,
    onClearChat: () -> Unit,
    onRetry: () -> Unit,
    onCitationTap: (Long) -> Unit,
    // Tablet form factor (null = phone): caps the chat thread + composer
    // column so messages stay legible on 8" (640 dp) / 10" (760 dp) widths
    // rather than stretching edge-to-edge. The rail stays visible — this is
    // a tablet route, not a Player full-bleed.
    size: TabletSize? = null,
) {
    val c = LocalKofipodColors.current
    val chatCapWidth: androidx.compose.ui.unit.Dp? =
        when (size) {
            null -> null
            // 8"P: 800-dp width minus the icon-only rail (~64 dp) already lands
            // at ~736 dp, which is under the 8"L cap. Applying 640 dp here would
            // visibly narrow the chat thread on a route where the rail is always
            // present (per plan §10.4 — full-screen AskGemini keeps the rail).
            TabletSize.Tablet8Port -> null
            TabletSize.Tablet8Land -> 640.dp
            TabletSize.Tablet10Port, TabletSize.Tablet10Land -> 760.dp
        }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg),
        contentAlignment = Alignment.TopCenter,
    ) {
        AskGeminiBody(
            state = state,
            composerText = composerText,
            header = header,
            onBack = onBack,
            onComposerChange = onComposerChange,
            onSubmit = onSubmit,
            onSubmitPreset = onSubmitPreset,
            onClearChat = onClearChat,
            onRetry = onRetry,
            onCitationTap = onCitationTap,
            modifier =
                if (chatCapWidth != null) {
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = chatCapWidth)
                        .fillMaxWidth()
                } else {
                    Modifier.fillMaxSize()
                },
        )
    }
}

@Composable
private fun AskGeminiBody(
    state: DiscussUiState,
    composerText: String,
    header: EpisodeHeader,
    onBack: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitPreset: (String) -> Unit,
    onClearChat: () -> Unit,
    onRetry: () -> Unit,
    onCitationTap: (Long) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        AskGeminiHeader(
            header = header,
            showClear = (state as? DiscussUiState.Ready)?.messages?.isNotEmpty() == true,
            onBack = onBack,
            onClear = onClearChat,
        )
        if ((state as? DiscussUiState.Ready)?.audioTurnWarningVisible == true) {
            AudioTurnWarningBanner()
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when (state) {
                DiscussUiState.Hidden -> Unit
                DiscussUiState.NoSource -> NoSourcePane()
                is DiscussUiState.Ready -> {
                    if (state.messages.isEmpty()) {
                        IdlePane(state = state, onSubmitPreset = onSubmitPreset)
                    } else {
                        ChatPane(
                            messages = state.messages,
                            inFlight = state.inFlight,
                            error = state.error,
                            progress = state.progress,
                            onCitationTap = onCitationTap,
                            onRetry = onRetry,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Composer(
            text = composerText,
            inFlight = (state as? DiscussUiState.Ready)?.inFlight == true,
            onChange = onComposerChange,
            onSubmit = onSubmit,
        )
    }
}

@Composable
private fun AskGeminiHeader(
    header: EpisodeHeader,
    showClear: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, contentDesc = "Back") {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            AiPillChip(label = "ASK GEMINI")
            Spacer(Modifier.height(4.dp))
            Text(
                header.title.ifBlank { "This episode" },
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showClear) {
            IconButton(onClick = onClear, contentDesc = "Clear chat") {
                KPIcon(name = KPIconName.Trash, color = c.textMute, size = 18.dp, strokeWidth = 1.6f)
            }
        }
    }
}

@Composable
private fun IconButton(
    onClick: () -> Unit,
    contentDesc: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun IdlePane(
    state: DiscussUiState.Ready,
    onSubmitPreset: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "What do you want\nto know about this episode?",
                color = c.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 32.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Replies cite the timestamp they came from. Discuss runs against the " +
                    "episode's transcript, or the downloaded audio when no transcript exists.",
                color = c.textSoft,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel("SUGGESTED")
            Spacer(Modifier.height(8.dp))
        }
        items(state.suggestions) { suggestion ->
            SuggestionCard(
                text = suggestion,
                onClick = { onSubmitPreset(suggestion) },
                testTag = "askGeminiSuggestion",
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionLabel("QUICK PROMPTS")
            Spacer(Modifier.height(10.dp))
            QuickPromptRow(prompts = state.quickPrompts, onTap = onSubmitPreset)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = LocalKofipodColors.current
    Text(
        text,
        color = c.textMute,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickPromptRow(
    prompts: List<String>,
    onTap: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        prompts.forEach { prompt ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.purpleTint)
                    .clickable { onTap(prompt) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("askGeminiQuickPrompt"),
            ) {
                Text(prompt, color = c.purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChatPane(
    messages: List<DiscussMessage>,
    inFlight: Boolean,
    error: AiError?,
    progress: DiscussProgress?,
    onCitationTap: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Auto-scroll to the latest message every time the list grows. Without
    // this, a long answer pushes itself off-screen the moment it lands and
    // the user has to scroll manually to see the reply.
    LaunchedEffect(messages.size, inFlight) {
        val targetIndex = (messages.size - 1 + if (inFlight) 1 else 0).coerceAtLeast(0)
        if (targetIndex > 0) listState.animateScrollToItem(targetIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages) { msg ->
            MessageBubble(message = msg, onCitationTap = onCitationTap)
        }
        // Staged progress wins over the typing indicator: the upload is what's
        // actually slow on the first audio send (~30s), and showing dots for
        // a 60 MB upload would be misleading. After the upload finishes,
        // `progress` clears and the standard typing indicator takes over for
        // the chat call itself.
        if (progress != null) {
            item { UploadProgressBubble(progress) }
        } else if (inFlight) {
            item { TypingIndicator() }
        }
        if (error != null) {
            item { ErrorBubble(error = error, onRetry = onRetry) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: DiscussMessage,
    onCitationTap: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    val isUser = message.role == DiscussRole.User
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isUser) c.purple else c.surface)
                    .border(1.dp, if (isUser) c.purple else c.border, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag(if (isUser) "askGeminiUserBubble" else "askGeminiModelBubble"),
        ) {
            Text(
                message.content,
                color = if (isUser) Color.White else c.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (!isUser && message.citations.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CitationStrip(citations = message.citations, onCitationTap = onCitationTap)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CitationStrip(
    citations: List<DiscussCitation>,
    onCitationTap: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        citations.forEach { citation ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.pinkSoft)
                    .clickable { onCitationTap(citation.timestampMs) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("askGeminiCitation"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KPIcon(name = KPIconName.Clock, color = c.pink, size = 10.dp, strokeWidth = 1.6f)
                Spacer(Modifier.width(4.dp))
                Text(
                    citation.label,
                    color = c.pink,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val c = LocalKofipodColors.current
    Row {
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("askGeminiTyping"),
        ) {
            // Three dots is enough cue without animating — the message list
            // jumps to this row, so movement comes from the scroll, not the
            // glyph. Static keeps the recomposition cost flat.
            Text("• • •", color = c.textMute, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorBubble(
    error: AiError,
    onRetry: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val copy =
        when (error) {
            AiError.NoKey -> "Set up your Gemini key in Settings to ask questions."
            AiError.KeyInvalid -> "Your Gemini key was rejected. Update it in Settings."
            // Audio Q&A burns more quota per turn than transcript chats, so the
            // free-tier limit is easier to hit. Steer the user toward the
            // self-serve fix rather than just asking them to wait.
            AiError.RateLimited ->
                "Gemini's busy or you're at your free-tier limit. Try again in a few minutes, " +
                    "or check your usage at aistudio.google.com."
            AiError.Network -> "Couldn't reach Gemini. Check your connection."
            AiError.TranscriptUnavailable -> "Couldn't fetch this episode's transcript or audio."
            AiError.AudioTooLong -> "This episode is too long to discuss in this version."
            // Differentiate 5xx (Gemini server-side) from anything else so
            // the user knows whether to retry or whether their request was
            // structurally rejected. The status code is shown when present
            // because it's actionable for support / triage and never reveals
            // the prompt or response body.
            is AiError.Unknown ->
                when {
                    error.statusCode != null && error.statusCode in 500..599 ->
                        "Gemini hit a server-side error (${error.statusCode}). Try again."
                    error.statusCode != null ->
                        "Something went wrong (status ${error.statusCode}). Try again."
                    else -> "Something went wrong. Try again."
                }
        }
    val showRetry =
        when (error) {
            // Configuration / content-shape errors don't recover by re-sending
            // the same message — the user needs to fix their key, pick a
            // different episode, etc. Hiding the button avoids tempting them
            // into a loop that can't help.
            AiError.NoKey, AiError.KeyInvalid, AiError.TranscriptUnavailable, AiError.AudioTooLong -> false
            AiError.RateLimited, AiError.Network, is AiError.Unknown -> true
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
                    .border(1.dp, c.warn, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("askGeminiError"),
            ) {
                Text(copy, color = c.text, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (showRetry) {
            Row {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(999.dp))
                        .clickable { onRetry() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("askGeminiRetry")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Retry"
                        },
                ) {
                    Text("Retry", color = c.purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NoSourcePane() {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No source yet",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This episode has no published transcript. Download it to discuss the audio.",
            color = c.textSoft,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * Banner anchored above the message list when the user has sent enough audio
 * turns to materially eat into their Gemini quota. Audio sessions re-process
 * the entire episode on every turn — a 10-turn chat is roughly 10× the
 * tokens of a single summary call.
 */
@Composable
private fun AudioTurnWarningBanner() {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.warn.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("askGeminiAudioTurnWarning"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No icon — the warm fill already signals "advisory", and we don't
        // want a hard-stop alert glyph here. Bold lead word stands in for it.
        Text(
            "Heads up — audio chats re-read the episode every turn, " +
                "so long sessions burn your Gemini quota faster. " +
                "Trash the chat to start fresh anytime.",
            color = c.text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

/**
 * Staged-progress card for the **first** audio-backed send in a session.
 * Replaces the typing indicator while the upload is in flight so the user
 * sees forward motion (Uploading → Analysing) rather than three static dots
 * for ~30 seconds. Cancel lives on the Ask Gemini header's clear-chat
 * affordance, which already cancels the in-flight job via `clearForEpisode`.
 */
@Composable
private fun UploadProgressBubble(progress: DiscussProgress) {
    val c = LocalKofipodColors.current
    val label =
        when (progress.stage) {
            DiscussProgressStage.Uploading -> {
                // Surface byte-level progress when the chunked uploader has
                // reported in. The numerator rounds down so the displayed
                // progress never overshoots the denominator mid-upload.
                val uploaded = progress.uploadedBytes
                val total = progress.sizeBytes
                if (uploaded != null && total != null && total > 0L) {
                    "Uploading audio… ${formatMbProgress(uploaded, total)}"
                } else {
                    "Uploading audio…"
                }
            }
            DiscussProgressStage.Analysing -> "Analysing audio…"
        }
    val fraction =
        if (
            progress.stage == DiscussProgressStage.Uploading &&
            progress.uploadedBytes != null &&
            progress.sizeBytes != null &&
            progress.sizeBytes > 0L
        ) {
            (progress.uploadedBytes.toFloat() / progress.sizeBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    Row {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("askGeminiUploadProgress"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(label, color = c.textSoft, fontSize = 13.sp, lineHeight = 18.sp)
            }
            // Bar only when we have a real fraction — Analysing and the
            // first-tick window keep the bubble at its original height so
            // the chat doesn't jitter on every stage flip.
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = c.pink,
                    trackColor = c.pinkSoft,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .testTag("askGeminiUploadProgressBar"),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    inFlight: Boolean,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("askGeminiComposer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    "Ask Gemini about this episode…",
                    color = c.textMute,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onChange,
                singleLine = false,
                maxLines = 4,
                cursorBrush = SolidColor(c.pink),
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().testTag("askGeminiComposerInput"),
            )
        }
        Spacer(Modifier.width(8.dp))
        SendBubble(
            enabled = text.isNotBlank() && !inFlight,
            onClick = onSubmit,
        )
    }
}
