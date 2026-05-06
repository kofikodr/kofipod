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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.RenderProgress
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Snippet editor: waveform-based trim, title/caption fields, format chip, and
 * a bottom CTA strip that switches between Idle / InFlight / Complete states
 * driven by [RenderProgress].
 *
 * All mutations route through [SnippetEditorViewModel] to keep persistence and
 * render-launch order single-sourced. The screen stays open after
 * [SnippetEditorViewModel.saveAndRender] — the user leaves via Cancel or Share
 * once the strip reaches Complete.
 */
@Composable
fun SnippetEditorScreen(
    snippetId: String,
    onBack: () -> Unit,
    viewModel: SnippetEditorViewModel = koinViewModel(parameters = { parametersOf(snippetId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.progress) {
        val p = state.progress
        if (p is RenderProgress.Failed) {
            // Dismiss any visible snackbar so the new failure message
            // gets a fresh timer rather than snapping mid-wait.
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar("Render failed: ${p.message}")
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = c.pink)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
        ) {
            SnippetEditorTopBar(onBack = onBack)

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                SnippetWaveform(
                    samples = state.waveform,
                    durationMs = state.episodeDurationMs,
                    startMs = state.startMs,
                    endMs = state.endMs,
                    playheadMs = state.previewPositionMs,
                    onStartChanged = viewModel::setStart,
                    onEndChanged = viewModel::setEnd,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SnippetTrimChips(startMs = state.startMs, endMs = state.endMs)
                    SnippetPreviewControl(isPlaying = state.previewing, onTap = viewModel::previewToggle)
                }

                OutlinedTextField(
                    value = state.caption,
                    onValueChange = viewModel::setCaption,
                    label = { Text("Caption") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    minLines = 2,
                    maxLines = 4,
                )

                SnippetFormatChip(
                    selected = state.format,
                    durationMs = (state.endMs - state.startMs),
                    onSelect = viewModel::setFormat,
                )
            }
        }

        // Bottom CTA strip — varies with progress. Stacked with snackbar above it.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Snackbar above the strip so they never overlap.
            SnackbarHost(hostState = snackbarHost)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg)
                    .padding(20.dp),
            ) {
                when (val p = state.progress) {
                    is RenderProgress.InFlight ->
                        RenderingStrip(
                            fraction = p.fraction,
                            onCancel = {
                                viewModel.cancelRender()
                                onBack()
                            },
                        )
                    is RenderProgress.Complete -> ReadyStrip(onShare = onBack)
                    else ->
                        IdleStrip(
                            onCancel = onBack,
                            onRenderAndShare = viewModel::saveAndRender,
                        )
                }
            }
        }
    }
}

@Composable
private fun IdleStrip(
    onCancel: () -> Unit,
    onRenderAndShare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
        KPButton(label = "Render & Share", onClick = onRenderAndShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun RenderingStrip(
    fraction: Float,
    onCancel: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = c.pink)
        Text("Rendering ${(fraction * 100).toInt()}%", color = c.textMute, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Rendering…", onClick = { }, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun ReadyStrip(onShare: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ready · opening share sheet", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onShare, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Share", onClick = onShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
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
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text("Snippet", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
}
