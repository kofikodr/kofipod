// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.config.AppInfo
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.primitives.SettingRow
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private const val MIN_CAP_BYTES: Long = 512L * 1024 * 1024
private const val MAX_CAP_BYTES: Long = 8L * 1024 * 1024 * 1024
private const val MIN_STREAM_CACHE_BYTES: Long = 128L * 1024 * 1024
private const val MAX_STREAM_CACHE_BYTES: Long = 2L * 1024 * 1024 * 1024

@Composable
fun SettingsScreen(
    onOpenScheduler: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 40.dp),
    ) {
        Text(
            "Settings",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
        )
        SectionLabel("Backup", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Folder,
            title = "Automatic backup",
            subtitle =
                "App data is automatically backed up if it's enabled in your phone's " +
                    "Settings → System → Backup. Audio downloads are not included.",
        )

        SectionLabel("Appearance", topSpacing = 22.dp)
        ThemeModeSelector(
            selected = state.themeMode,
            onSelect = viewModel::setTheme,
        )

        SectionLabel("Auto-downloader", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Radar,
            title = "Daily check for new episodes",
            subtitle = "Runs about once a day while you have a network connection",
            trailing = {
                PinkSwitch(
                    checked = state.dailyCheck,
                    onCheckedChange = viewModel::setDailyCheck,
                    testTag = "dailyCheckSwitch",
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        SettingRow(
            icon = KPIconName.Radar,
            title = "Download on Wi-Fi only",
            subtitle = "Cellular downloads are deferred until you're back on Wi-Fi",
            trailing = {
                PinkSwitch(
                    checked = state.wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                    testTag = "wifiOnlySwitch",
                )
            },
        )

        Spacer(Modifier.height(12.dp))
        MaxDownloadSizeCard(
            bytes = state.storageCapBytes,
            onChange = { viewModel.setCap(it) },
        )

        SectionLabel("Playback cache", topSpacing = 22.dp)
        PlaybackCacheCard(
            capBytes = state.streamCacheCapBytes,
            usedBytes = state.streamCacheUsedBytes,
            onChange = { viewModel.setStreamCacheCap(it) },
        )

        // Debug-only entry point to scheduler info screen; kept intentionally minimal.
        Spacer(Modifier.height(24.dp))
        Text(
            "Scheduler details →",
            color = c.textMute,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clickable { onOpenScheduler() }
                    .padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Kofipod · v${AppInfo.versionName}",
            color = c.textMute,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Podcast data powered by Podcast Index",
            color = c.textMute,
            fontSize = 11.sp,
        )
    }
}

// --------------------------------------------------------------------------
// Theme mode selector
// --------------------------------------------------------------------------

@Composable
private fun ThemeModeSelector(
    selected: KofipodThemeMode,
    onSelect: (KofipodThemeMode) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(4.dp),
    ) {
        KofipodThemeMode.entries.forEach { mode ->
            val active = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (active) c.purple else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        when (mode) {
                            KofipodThemeMode.System -> "System"
                            KofipodThemeMode.Light -> "Light"
                            KofipodThemeMode.Dark -> "Dark"
                        },
                    color = if (active) Color.White else c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Max auto-download size card — gradient slider
// --------------------------------------------------------------------------

@Composable
private fun MaxDownloadSizeCard(
    bytes: Long,
    onChange: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Max auto-download size",
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Oldest unplayed episodes are removed first",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
            Text(
                formatGb(bytes),
                color = c.pink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))
        GradientSlider(
            value = bytes.coerceIn(MIN_CAP_BYTES, MAX_CAP_BYTES).toFloat(),
            valueRange = MIN_CAP_BYTES.toFloat()..MAX_CAP_BYTES.toFloat(),
            onValueChange = { onChange(it.toLong()) },
            modifier = Modifier.testTag("storageCapSlider"),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "500 MB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "8 GB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// --------------------------------------------------------------------------
// Playback cache card — streaming cache cap with live "used" readout
// --------------------------------------------------------------------------

@Composable
private fun PlaybackCacheCard(
    capBytes: Long,
    usedBytes: Long,
    onChange: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Streaming cache",
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Audio is cached as you listen. Changes apply on next app restart.",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
            Text(
                formatSize(capBytes),
                color = c.pink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))
        GradientSlider(
            value = capBytes.coerceIn(MIN_STREAM_CACHE_BYTES, MAX_STREAM_CACHE_BYTES).toFloat(),
            valueRange = MIN_STREAM_CACHE_BYTES.toFloat()..MAX_STREAM_CACHE_BYTES.toFloat(),
            onValueChange = { onChange(it.toLong()) },
            modifier = Modifier.testTag("streamCacheCapSlider"),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "128 MB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Currently using ${formatSize(usedBytes)}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "2 GB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Custom slider with a purple→pink gradient active track, grey inactive track,
 * and a white thumb sitting inside a pink halo. Drag-to-update; no tap-snap.
 */
@Composable
private fun GradientSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val density = LocalDensity.current
    val trackHeight = 6.dp
    val thumbRadius = 10.dp
    val haloRadius = 14.dp

    val fraction =
        remember(value, valueRange) {
            if (valueRange.endInclusive == valueRange.start) {
                0f
            } else {
                (
                    (value - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start)
                ).coerceIn(0f, 1f)
            }
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(haloRadius * 2 + 8.dp),
    ) {
        val maxPx = with(density) { maxWidth.toPx() }
        var dragX by remember { mutableStateOf(0f) }

        fun emit(x: Float) {
            val clamped = x.coerceIn(0f, maxPx)
            val f = if (maxPx == 0f) 0f else clamped / maxPx
            val v = valueRange.start + f * (valueRange.endInclusive - valueRange.start)
            onValueChange(v)
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(maxPx) {
                        detectTapGestures(onTap = { emit(it.x) })
                    }
                    .pointerInput(maxPx) {
                        detectDragGestures(
                            onDragStart = {
                                dragX = it.x
                                emit(it.x)
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragX += drag.x
                                emit(dragX)
                            },
                        )
                    },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val trackH = with(density) { trackHeight.toPx() }
                val y = h / 2f
                val thumbR = with(density) { thumbRadius.toPx() }
                val haloR = with(density) { haloRadius.toPx() }

                // Inactive track
                drawRoundRect(
                    color = c.purpleTint,
                    topLeft = Offset(0f, y - trackH / 2f),
                    size = Size(w, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH),
                )
                // Active gradient track
                val activeW = w * fraction
                if (activeW > 0f) {
                    drawRoundRect(
                        brush =
                            Brush.horizontalGradient(
                                colors = listOf(c.purple, c.pink),
                                startX = 0f,
                                endX = w,
                            ),
                        topLeft = Offset(0f, y - trackH / 2f),
                        size = Size(activeW, trackH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH),
                    )
                }
                // Halo + thumb
                val cx = activeW.coerceIn(thumbR, w - thumbR)
                drawCircle(
                    color = c.pink.copy(alpha = 0.22f),
                    radius = haloR,
                    center = Offset(cx, y),
                )
                drawCircle(
                    color = c.pink,
                    radius = thumbR + 1f,
                    center = Offset(cx, y),
                )
                drawCircle(
                    color = Color.White,
                    radius = thumbR - 2f,
                    center = Offset(cx, y),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Shared pink switch
// --------------------------------------------------------------------------

@Composable
private fun PinkSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val c = LocalKofipodColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.pink,
                checkedBorderColor = c.pink,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.purpleTint,
                uncheckedBorderColor = c.border,
            ),
    )
}

// --------------------------------------------------------------------------
// Formatting
// --------------------------------------------------------------------------

private fun formatGb(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        val whole = gb.toInt()
        val tenths = ((gb - whole) * 10).roundToInt().coerceIn(0, 9)
        "$whole.$tenths GB"
    } else {
        val mb = (bytes / (1024L * 1024L)).toInt()
        "$mb MB"
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) formatGb(bytes) else "${(bytes / (1024L * 1024L)).toInt()} MB"
}
