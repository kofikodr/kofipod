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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ai.DiscussMessage
import app.kofipod.ai.DiscussRole
import app.kofipod.ai.DiscussUiState
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Discuss tab body. Two visual states inside the unified [PanelCard] frame:
 * Idle (no chat yet) shows the AI eyebrow, a single suggestion card, and a
 * composer-stub that opens the full Ask Gemini screen on tap; Active (chat
 * exists) shows the same chrome plus a "Continue your chat" card with a
 * trashcan to wipe.
 *
 * The composer is a stub — tapping it (or any suggestion) navigates to the
 * full-screen [app.kofipod.ui.screens.askgemini.AskGeminiScreen] where the
 * real input + message thread live. Mirrors the mock: the tab card is the
 * entry point, the chat is the destination.
 */
@Composable
fun DiscussTabPanel(
    episodeId: String,
    onOpenAskGemini: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscussViewModel = koinViewModel(parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    DiscussTabPanelContent(
        state = state,
        onOpenAskGemini = onOpenAskGemini,
        onClearChat = viewModel::clearChat,
        modifier = modifier,
    )
}

/**
 * Stateless rendering of the tab card. Lifted out so future Paparazzi
 * snapshots can drive each branch with hand-rolled state, mirroring
 * [AiSummaryPanelContent]'s shape but without Koin or any VM machinery.
 */
@Composable
internal fun DiscussTabPanelContent(
    state: DiscussUiState,
    onOpenAskGemini: () -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        // Hidden is unreachable from this composable in practice — the parent
        // gates the entire Discuss tab on `summaryEnabled` (which mirrors the
        // same key-configured flag). Keep the branch as a safe no-op so a
        // race between flow updates can't render an Error / blank state.
        DiscussUiState.Hidden -> Unit
        DiscussUiState.NoSource -> NoSourceCard(modifier = modifier)
        is DiscussUiState.Ready ->
            ReadyCard(
                state = state,
                onOpenAskGemini = onOpenAskGemini,
                onClearChat = onClearChat,
                modifier = modifier,
            )
    }
}

@Composable
private fun NoSourceCard(modifier: Modifier) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        AiPillChip(label = "AI DISCUSS")
        Spacer(Modifier.height(10.dp))
        Text(
            "Discuss runs against the transcript or the downloaded audio. " +
                "This episode has neither yet — download it to chat about the audio.",
            color = c.textSoft,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun ReadyCard(
    state: DiscussUiState.Ready,
    onOpenAskGemini: () -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalKofipodColors.current
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiPillChip(label = "AI DISCUSS")
            Spacer(Modifier.weight(1f))
            // Trashcan affordance is only meaningful when there's something to
            // clear. Surfacing it on Idle would dangle a no-op next to the
            // first-touch entry — the parent screen's chrome stays cleaner.
            if (state.messages.isNotEmpty()) {
                ClearChatButton(onClearChat)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Ask Gemini about this episode. Answers cite timestamps from the source.",
            color = c.textSoft,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        if (state.audioTurnWarningVisible) {
            Spacer(Modifier.height(10.dp))
            AudioTurnWarning()
        }
        if (state.messages.isEmpty()) {
            IdleBody(
                state = state,
                onOpenAskGemini = onOpenAskGemini,
            )
        } else {
            ActiveBody(
                state = state,
                onOpenAskGemini = onOpenAskGemini,
            )
        }
    }
}

@Composable
private fun AudioTurnWarning() {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.warn.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("discussAudioTurnWarning"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Heads up — long audio chats use more of your Gemini quota.",
            color = c.text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun IdleBody(
    state: DiscussUiState.Ready,
    onOpenAskGemini: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Spacer(Modifier.height(14.dp))
    Text(
        "TRY ONE OF THESE",
        color = c.textMute,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    // First suggestion is the focal card on the tab. The full Ask Gemini
    // screen surfaces all four; we show one here to keep the tab compact and
    // give the user a concrete first tap.
    val firstSuggestion = state.suggestions.firstOrNull()
    if (firstSuggestion != null) {
        SuggestionCard(text = firstSuggestion, onClick = onOpenAskGemini, testTag = "discussSuggestion")
        Spacer(Modifier.height(12.dp))
    }
    ComposerStub(onClick = onOpenAskGemini)
}

@Composable
private fun ActiveBody(
    state: DiscussUiState.Ready,
    onOpenAskGemini: () -> Unit,
) {
    Spacer(Modifier.height(14.dp))
    ContinueChatCard(
        messageCount = state.messages.size,
        lastUser = lastUserMessage(state.messages),
        onClick = onOpenAskGemini,
    )
    Spacer(Modifier.height(12.dp))
    ComposerStub(onClick = onOpenAskGemini)
}

@Composable
private fun ContinueChatCard(
    messageCount: Int,
    lastUser: String?,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.purpleTint)
            .clickable { onClick() }
            .padding(14.dp)
            .testTag("discussContinueChat"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.purple),
            contentAlignment = Alignment.Center,
        ) {
            // Reuses the existing chevron-down glyph rotated visually as a
            // chat-bubble cue would be overkill — pink sparkle reads as a
            // distinct AI surface, purple background distinguishes it from
            // the suggestion card above.
            KPIcon(name = KPIconName.Sparkle, color = Color.White, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Continue your chat",
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                continuationCaption(messageCount, lastUser),
                color = c.textSoft,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 16.dp)
    }
}

@Composable
private fun ComposerStub(onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("discussComposerStub"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Ask Gemini about this episode…",
            color = c.textMute,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        SendBubble(enabled = false, onClick = onClick)
    }
}

@Composable
private fun ClearChatButton(onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable { onClick() }
            .semantics {
                role = Role.Button
                contentDescription = "Clear chat"
            }
            .testTag("discussClearChat"),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.Trash, color = c.textMute, size = 16.dp, strokeWidth = 1.6f)
    }
}

private fun lastUserMessage(messages: List<DiscussMessage>): String? =
    messages.lastOrNull { it.role == DiscussRole.User }?.content?.takeIf { it.isNotBlank() }

private fun continuationCaption(
    messageCount: Int,
    lastUser: String?,
): String {
    val countLabel = if (messageCount == 1) "1 message" else "$messageCount messages"
    return if (lastUser != null) {
        "$countLabel · last asked \"${lastUser.trim().take(80)}\""
    } else {
        countLabel
    }
}
