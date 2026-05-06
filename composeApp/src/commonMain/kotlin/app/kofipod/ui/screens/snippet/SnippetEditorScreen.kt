// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.RenderProgress
import app.kofipod.snippets.SnippetWindow
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Snippet editor: title field, start/end nudge rows, format hint, render
 * trigger. The screen is stateless beyond what the [SnippetEditorViewModel]
 * exposes — every interaction routes through the VM so the persistence +
 * render-launch order stays single-sourced.
 *
 * Format display reads "MP3 · MP4 coming soon" by intent: Slice 4 will add
 * MP4 support via Media3 Transformer's video graph; surfacing it here as a
 * roadmap hint avoids a future UX surprise without enabling a chip that
 * would today produce a broken render.
 */
@Composable
fun SnippetEditorScreen(
    snippetId: String,
    onBack: () -> Unit,
    viewModel: SnippetEditorViewModel = koinViewModel(parameters = { parametersOf(snippetId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    if (state.loading) {
        Box(
            Modifier.fillMaxSize().background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = c.pink)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        SnippetEditorTopBar(onBack = onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            TrimRow(
                label = "Start",
                value = state.startMs,
                onMinus5 = { viewModel.setStart(state.startMs - FIVE_SECONDS_MS) },
                onMinus1 = { viewModel.setStart(state.startMs - ONE_SECOND_MS) },
                onPlus1 = { viewModel.setStart(state.startMs + ONE_SECOND_MS) },
                onPlus5 = { viewModel.setStart(state.startMs + FIVE_SECONDS_MS) },
            )

            TrimRow(
                label = "End",
                value = state.endMs,
                onMinus5 = { viewModel.setEnd(state.endMs - FIVE_SECONDS_MS) },
                onMinus1 = { viewModel.setEnd(state.endMs - ONE_SECOND_MS) },
                onPlus1 = { viewModel.setEnd(state.endMs + ONE_SECOND_MS) },
                onPlus5 = { viewModel.setEnd(state.endMs + FIVE_SECONDS_MS) },
            )

            Text(
                "Format: MP3  ·  MP4 coming soon",
                color = c.textMute,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(8.dp))
            KPButton(
                label = if (state.progress !is RenderProgress.Idle) "Rendering…" else "Render & Share",
                onClick = {
                    if (state.progress is RenderProgress.Idle) viewModel.saveAndRender()
                },
                style = KPButtonStyle.PrimaryPink,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SnippetEditorTopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text("Snippet", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
}

@Composable
private fun TrimRow(
    label: String,
    value: Long,
    onMinus5: () -> Unit,
    onMinus1: () -> Unit,
    onPlus1: () -> Unit,
    onPlus5: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column {
        Text(
            "$label: ${SnippetWindow.formatTimestampDeci(value)}",
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KPButton(label = "-5s", onClick = onMinus5, style = KPButtonStyle.Outline)
            KPButton(label = "-1s", onClick = onMinus1, style = KPButtonStyle.Outline)
            KPButton(label = "+1s", onClick = onPlus1, style = KPButtonStyle.Outline)
            KPButton(label = "+5s", onClick = onPlus5, style = KPButtonStyle.Outline)
        }
    }
}

private const val ONE_SECOND_MS = 1_000L
private const val FIVE_SECONDS_MS = 5_000L
