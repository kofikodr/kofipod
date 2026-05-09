// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.RenderProgress
import app.kofipod.snippets.SnippetWindow
import app.kofipod.ui.primitives.KPBadge
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Snippet editor: episode header, waveform-based trim with overlaid handles,
 * title/caption fields, format chip, and a bottom CTA strip that switches
 * between Idle / InFlight / Complete states driven by [RenderProgress].
 *
 * Layout follows the Slice 4 design (`docs/superpowers/plans/2026-05-06-…`):
 *   X · SNIPPET · PRO
 *   [episode header card]
 *   [waveform card: WAVEFORM label + selection time + bars + handles + axis + trim row]
 *   TITLE field
 *   CAPTION field
 *   FORMAT chip
 *   [bottom strip] varies with progress
 *
 * All mutations route through [SnippetEditorViewModel].
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
            // Dismiss any visible snackbar so the new failure message gets a
            // fresh timer rather than snapping mid-wait.
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
                .padding(bottom = 140.dp),
        ) {
            SnippetTopBar(onBack = onBack)

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EpisodeHeaderCard(
                    episodeTitle = state.episodeTitle,
                    podcastTitle = state.podcastTitle,
                    episodeNumber = state.episodeNumber,
                    artworkUrl = state.artworkUrl,
                    artworkSeed = state.artworkSeed,
                )

                WaveformCard(
                    state = state,
                    onStartChanged = viewModel::setStart,
                    onEndChanged = viewModel::setEnd,
                    onPreviewToggle = viewModel::previewToggle,
                )

                LabeledField(label = "TITLE") {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::setTitle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                LabeledField(label = "CAPTION") {
                    OutlinedTextField(
                        value = state.caption,
                        onValueChange = viewModel::setCaption,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 180.dp),
                        minLines = 3,
                        maxLines = 5,
                    )
                }

                LabeledField(label = "FORMAT") {
                    SnippetFormatChip(
                        selected = state.format,
                        durationMs = (state.endMs - state.startMs),
                        onSelect = viewModel::setFormat,
                    )
                }
            }
        }

        // Bottom CTA strip — varies with progress. Stacked with snackbar above it.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(hostState = snackbarHost)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                when (val p = state.progress) {
                    is RenderProgress.InFlight ->
                        RenderingStrip(
                            fraction = p.fraction,
                            format = state.format.name,
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
private fun SnippetTopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Close, color = c.text, size = 22.dp)
        }
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "SNIPPET",
                color = c.textMute,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            KPBadge("PRO")
        }
    }
}

@Composable
private fun EpisodeHeaderCard(
    episodeTitle: String,
    podcastTitle: String,
    episodeNumber: Int?,
    artworkUrl: String,
    artworkSeed: Int,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 56.dp,
            seed = artworkSeed,
            label = podcastTitle,
            radius = 12.dp,
            model = artworkUrl.ifBlank { null },
            contentDescription = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                episodeTitle.ifBlank { "Untitled episode" },
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            val subtitle =
                buildString {
                    append(podcastTitle.ifBlank { "—" }.uppercase())
                    episodeNumber?.let { append(" · EP ").append(it) }
                }
            Text(
                subtitle,
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WaveformCard(
    state: SnippetEditorUiState,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    onPreviewToggle: () -> Unit,
) {
    val c = LocalKofipodColors.current
    // Compute the viewport ONCE per snippet — i.e. on first composition after
    // load() resolves. Recomputing on every state write was the source of the
    // "handle snaps back on release" bug: the auto-zoom kept re-centring on
    // the new trim, so dragging Start right shrank the trim, the viewport
    // re-centred around the smaller trim, and Start ended up at the same
    // pixel-percentage it started at. Keying on snippet.id keeps the editor
    // reusable across navigations to different snippets without resetting
    // mid-edit.
    val (viewStart, viewEnd) =
        remember(state.snippet?.id) {
            computeWaveformViewport(state.startMs, state.endMs, state.episodeDurationMs)
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "WAVEFORM",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                SnippetWindow.formatDuration(state.endMs - state.startMs),
                color = c.pink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        SnippetWaveform(
            samples = state.waveform,
            durationMs = state.episodeDurationMs,
            startMs = state.startMs,
            endMs = state.endMs,
            playheadMs = state.previewPositionMs,
            viewportStartMs = viewStart,
            viewportEndMs = viewEnd,
            onStartChanged = onStartChanged,
            onEndChanged = onEndChanged,
        )
        TimeAxis(
            viewportStartMs = viewStart,
            viewportEndMs = viewEnd,
            startMs = state.startMs,
            endMs = state.endMs,
        )
        SnippetTrimChips(
            startMs = state.startMs,
            endMs = state.endMs,
            isPreviewing = state.previewing,
            onPreviewToggle = onPreviewToggle,
        )
    }
}

@Composable
private fun TimeAxis(
    viewportStartMs: Long,
    viewportEndMs: Long,
    startMs: Long,
    endMs: Long,
) {
    val c = LocalKofipodColors.current
    // Labels match the bar field beneath them: viewport edges on the outside,
    // trim positions in pink. With the zoom in place the trim labels sit
    // visibly inside the viewport edges instead of crashing into them.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            SnippetWindow.formatTimestamp(viewportStartMs),
            color = c.textMute,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text(
            SnippetWindow.formatTimestamp(startMs),
            color = c.pink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Text(
            SnippetWindow.formatTimestamp(endMs),
            color = c.pink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Text(
            SnippetWindow.formatTimestamp(viewportEndMs),
            color = c.textMute,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column {
        Text(
            label,
            color = c.textMute,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(6.dp))
        content()
    }
}

@Composable
private fun IdleStrip(
    onCancel: () -> Unit,
    onRenderAndShare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
        KPButton(label = "Render & share", onClick = onRenderAndShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun RenderingStrip(
    fraction: Float,
    format: String,
    onCancel: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Rendering $format…",
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                color = c.pink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = c.pink,
            trackColor = c.purpleTint,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Rendering…", onClick = { }, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun ReadyStrip(onShare: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.success.copy(alpha = 0.12f))
                .border(1.dp, c.success.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(c.success),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Check, color = Color.White, size = 14.dp, strokeWidth = 2.4f)
            }
            Spacer(Modifier.width(10.dp))
            Text("Ready · opening share sheet", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onShare, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Share", onClick = onShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
        }
    }
}
