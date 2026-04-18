// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.data.repo.DownloadRow
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodArtwork
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.max
import kotlin.math.min

/** Default auto-download cap shown in the hero card when Settings doesn't override it. */
private const val DEFAULT_CAP_GB: Double = 3.0
private const val BYTES_PER_GB: Double = 1024.0 * 1024.0 * 1024.0
private const val BYTES_PER_MB: Double = 1024.0 * 1024.0

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    val usedBytes: Long = state.completed.sumOf { it.totalBytes.coerceAtLeast(0L) }
    val capGb: Double = DEFAULT_CAP_GB
    val capBytes: Long = (capGb * BYTES_PER_GB).toLong()
    val fraction: Float = if (capBytes > 0) {
        (usedBytes.toDouble() / capBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 20.dp),
    ) {
        item {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Downloads",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(16.dp))
            AutoDownloadCapCard(
                usedBytes = usedBytes,
                capGb = capGb,
                fraction = fraction,
                episodeCount = state.completed.size,
            )
        }

        // DOWNLOADING
        if (state.downloading.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Downloading \u00B7 ${state.downloading.size}",
                    trailing = {
                        Text(
                            text = "Pause all",
                            color = c.pink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                state.downloading.forEach { viewModel.cancel(it.episodeId) }
                            },
                        )
                    },
                )
            }
            items(state.downloading, key = { "dl-${it.episodeId}" }) { d ->
                InProgressRow(d, onCancel = { viewModel.cancel(d.episodeId) })
            }
        }

        // UP NEXT (queued + paused)
        if (state.queued.isNotEmpty()) {
            item { SectionLabel(title = "Up next \u00B7 ${state.queued.size}") }
            items(state.queued, key = { "up-${it.episodeId}" }) { d ->
                QueuedRow(d)
            }
        }

        // DOWNLOADED
        if (state.completed.isNotEmpty()) {
            item {
                SectionLabel(
                    title = "Downloaded \u00B7 ${state.completed.size}",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Oldest first",
                                color = c.textSoft,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.size(4.dp))
                            KPIcon(
                                name = KPIconName.ChevronDown,
                                color = c.textSoft,
                                size = 14.dp,
                                strokeWidth = 2f,
                            )
                        }
                    },
                )
            }
            items(state.completed, key = { "done-${it.episodeId}" }) { d ->
                CompletedRow(d, onDelete = { viewModel.delete(d.episodeId) })
            }
        }

        // FAILED retains a simple section so users can dismiss errors.
        if (state.failed.isNotEmpty()) {
            item { SectionLabel(title = "Failed \u00B7 ${state.failed.size}") }
            items(state.failed, key = { "fail-${it.episodeId}" }) { d ->
                CompletedRow(d, onDelete = { viewModel.delete(d.episodeId) })
            }
        }

        if (state.downloading.isEmpty() && state.queued.isEmpty() &&
            state.completed.isEmpty() && state.failed.isEmpty()
        ) {
            item {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "No downloads yet. Tap \u2913 on an episode to download.",
                    color = c.textMute,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Hero: Auto-download cap card with progress ring
// ---------------------------------------------------------------------------

@Composable
private fun AutoDownloadCapCard(
    usedBytes: Long,
    capGb: Double,
    fraction: Float,
    episodeCount: Int,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(BorderStroke(1.dp, c.border), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                fraction = fraction,
                usedLabel = formatGb(usedBytes),
            )
            Spacer(Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Auto-download cap",
                    color = c.textSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatGb(usedBytes)} / ${formatCapGb(capGb)} GB",
                    color = c.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(fraction * 100).toInt()}% full \u00B7 $episodeCount episodes",
                    color = c.textMute,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float, usedLabel: String) {
    val c = LocalKofipodColors.current
    val ringSize = 72.dp
    val strokeDp = 8.dp
    Box(
        modifier = Modifier.size(ringSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeDp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)

            // Background track
            drawArc(
                color = c.purpleTint,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            val sweep = (fraction.coerceIn(0f, 1f)) * 360f
            if (sweep > 0f) {
                // Gradient stroke purple -> pink along a linear axis; linearGradient
                // is available everywhere and reads well over a partial arc.
                val brush = Brush.linearGradient(
                    colors = listOf(c.purple, c.pink),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
                drawArc(
                    brush = brush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = usedLabel,
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
            )
            Text(
                text = "GB USED",
                color = c.textMute,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Row variants
// ---------------------------------------------------------------------------

@Composable
private fun InProgressRow(d: DownloadRow, onCancel: () -> Unit) {
    val c = LocalKofipodColors.current
    val progress: Float = if (d.totalBytes > 0) {
        (d.downloadedBytes.toFloat() / d.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KofipodArtwork(
                size = 40.dp,
                seed = d.episodeId.hashCode(),
                label = (d.podcastTitle ?: d.episodeTitle ?: d.episodeId).take(2),
                radius = 10.dp,
                model = d.artworkUrl?.ifBlank { null },
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = d.episodeTitle ?: d.episodeId,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${d.podcastTitle ?: "\u2014"} \u00B7 ${d.source}",
                    color = c.textMute,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Close,
                    color = c.textSoft,
                    size = 18.dp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GradientProgressBar(progress = progress)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${(progress * 100).toInt()}% \u00B7 ${formatMb(d.downloadedBytes)}",
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = etaLabel(d),
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun QueuedRow(d: DownloadRow) {
    val c = LocalKofipodColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 40.dp,
            seed = d.episodeId.hashCode(),
            label = (d.podcastTitle ?: d.episodeTitle ?: d.episodeId).take(2),
            radius = 10.dp,
            model = d.artworkUrl?.ifBlank { null },
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = d.episodeTitle ?: d.episodeId,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${d.podcastTitle ?: "\u2014"} \u00B7 ${d.source.uppercase()} \u00B7 ${formatMb(d.totalBytes.coerceAtLeast(d.downloadedBytes))}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        KPIcon(
            name = KPIconName.Clock,
            color = c.textSoft,
            size = 18.dp,
        )
    }
}

@Composable
private fun CompletedRow(d: DownloadRow, onDelete: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KofipodArtwork(
            size = 40.dp,
            seed = d.episodeId.hashCode(),
            label = (d.podcastTitle ?: d.episodeTitle ?: d.episodeId).take(2),
            radius = 10.dp,
            model = d.artworkUrl?.ifBlank { null },
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = d.episodeTitle ?: d.episodeId,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${d.podcastTitle ?: "\u2014"} \u00B7 ${completedCaption(d)}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, c.border), CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.Trash,
                color = c.textSoft,
                size = 16.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Gradient progress bar
// ---------------------------------------------------------------------------

@Composable
private fun GradientProgressBar(progress: Float) {
    val c = LocalKofipodColors.current
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.purpleTint),
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(c.purple, c.pink),
                        ),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private fun formatGb(bytes: Long): String {
    val gb = bytes.toDouble() / BYTES_PER_GB
    return trimNumber(gb, decimals = 2)
}

private fun formatCapGb(capGb: Double): String = trimNumber(capGb, decimals = 1)

private fun formatMb(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / BYTES_PER_MB
    return "${trimNumber(mb, decimals = 0)} MB"
}

private fun trimNumber(value: Double, decimals: Int): String {
    val rounded = roundTo(value, decimals)
    if (decimals <= 0) return rounded.toLong().toString()
    val whole = rounded.toLong()
    val frac = ((rounded - whole) * pow10(decimals)).toLong()
    if (frac == 0L) return whole.toString()
    val fracStr = frac.toString().padStart(decimals, '0').trimEnd('0')
    return if (fracStr.isEmpty()) whole.toString() else "$whole.$fracStr"
}

private fun roundTo(value: Double, decimals: Int): Double {
    val factor = pow10(decimals).toDouble()
    val scaled = value * factor
    val rounded = if (scaled >= 0) (scaled + 0.5).toLong() else -((-scaled) + 0.5).toLong()
    return rounded / factor
}

private fun pow10(n: Int): Long {
    var r = 1L
    repeat(max(0, n)) { r *= 10L }
    return r
}

private fun etaLabel(d: DownloadRow): String {
    val total = d.totalBytes
    val done = d.downloadedBytes
    val started = d.startedAt
    if (total <= 0 || done <= 0 || started == null || started <= 0) return "ETA \u2014"
    val nowApprox = started + 1L // without a clock source we can't compute true ETA
    val elapsedMs = max(1L, nowApprox - started)
    val bps = done.toDouble() / elapsedMs.toDouble()
    if (bps <= 0.0) return "ETA \u2014"
    val remaining = (total - done).coerceAtLeast(0L)
    val secs = min(60L * 60L, (remaining / (bps * 1000.0)).toLong())
    val m = secs / 60L
    val s = secs % 60L
    return "ETA ${m}m ${s}s"
}

private fun completedCaption(d: DownloadRow): String {
    val size = formatMb(d.totalBytes.coerceAtLeast(d.downloadedBytes))
    val age = when {
        d.completedAt != null -> "DONE"
        d.state == "Failed" -> "FAILED"
        else -> d.state.uppercase()
    }
    return "$age \u00B7 $size"
}

