// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.scheduler

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.background.SchedulerRun
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SchedulerInfoScreen(
    onBack: () -> Unit,
    viewModel: SchedulerInfoViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(name = KPIconName.Back, color = c.text, size = 20.dp, strokeWidth = 2.2f)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Daily check",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            HeroCard()
            Spacer(Modifier.height(14.dp))
            StatusCard(
                enabled = state.dailyEnabled,
                lastRun = state.runs.lastOrNull(),
            )

            SectionLabel("What \"roughly once a day\" means", topSpacing = 20.dp)

            NumberedCard(
                number = "1",
                title = "Battery-aware",
                body = "The OS may delay the check if you're on battery saver.",
            )
            Spacer(Modifier.height(8.dp))
            NumberedCard(
                number = "2",
                title = "Wi-Fi preferred",
                body = "When Wi-Fi Only is on, checks wait for a network you trust.",
            )
            Spacer(Modifier.height(8.dp))
            NumberedCard(
                number = "3",
                title = "Coalesced",
                body = "If you open the app, Kofipod checks right away and resets the clock.",
            )

            SectionLabel("Last 7 runs", topSpacing = 20.dp)
            LastRunsChart(runs = state.runs.takeLast(7))

            Spacer(Modifier.height(32.dp))
        }
    }
}

// --------------------------------------------------------------------------
// Hero gradient card
// --------------------------------------------------------------------------

@Composable
private fun HeroCard() {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(c.purple, c.purpleDeep),
                ),
            ),
    ) {
        // Decorative circles in the corner
        Canvas(Modifier.fillMaxSize()) {
            val big = size.height * 1.1f
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = big * 0.55f,
                center = Offset(size.width - big * 0.15f, size.height * 0.35f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = big * 0.35f,
                center = Offset(size.width - big * 0.55f, size.height * 0.9f),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = KPIconName.Radar,
                    color = Color.White,
                    size = 22.dp,
                    strokeWidth = 2f,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Checks once a day,\ngently.",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                lineHeight = 30.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Kofipod uses Android's WorkManager to poll your podcasts roughly " +
                    "once per day — when the OS decides it's cheap.",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

// --------------------------------------------------------------------------
// Status card
// --------------------------------------------------------------------------

@Composable
private fun StatusCard(
    enabled: Boolean,
    lastRun: SchedulerRun?,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (enabled) c.success else c.textMute),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (enabled) "Scheduler is on" else "Scheduler is off",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildStatusSubtitle(lastRun, enabled),
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            // SchedulerInfoViewModel is currently read-only (no toggle setter).
            // Keep the switch interactive-looking but make it a no-op. STUB:
            // wire through to scheduler + SettingsRepository when VM exposes it.
            onCheckedChange = {},
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
}

private fun buildStatusSubtitle(
    run: SchedulerRun?,
    enabled: Boolean,
): String {
    // Design calls for "LAST RUN 07:12 · NEXT ~06:00 TOMORROW" — VM doesn't expose
    // nextEta, so we derive a best-effort string. When there's no run yet, we
    // show a pre-run message instead of a fake time.
    val last = run?.let { formatTimeOfDay(it.at) }
    return when {
        !enabled -> "PAUSED · TOGGLE ON TO RESUME"
        last != null -> "LAST RUN $last · NEXT ~06:00 TOMORROW"
        else -> "NO RUNS YET · FIRST CHECK WILL APPEAR HERE"
    }
}

/**
 * Format a millis-since-epoch timestamp as HH:MM in local time.
 * Pure arithmetic on epoch-ms — no timezone handling — since the run log is
 * stored in local wall time (see SchedulerRunLog). Good enough for the mock
 * display and stable across KMP targets without pulling kotlinx-datetime.
 */
private fun formatTimeOfDay(epochMs: Long): String {
    val secondsOfDay = ((epochMs / 1000L) % 86400L + 86400L) % 86400L
    val hh = (secondsOfDay / 3600L).toInt()
    val mm = ((secondsOfDay / 60L) % 60L).toInt()
    return "${hh.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')}"
}

// --------------------------------------------------------------------------
// Numbered card
// --------------------------------------------------------------------------

@Composable
private fun NumberedCard(
    number: String,
    title: String,
    body: String,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number,
                color = c.purple,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                color = c.textMute,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// --------------------------------------------------------------------------
// Last 7 runs bar chart
// --------------------------------------------------------------------------

@Composable
private fun LastRunsChart(runs: List<SchedulerRun>) {
    val c = LocalKofipodColors.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        BarChart(
            runs = runs,
            purple = c.purple,
            pink = c.pink,
            track = c.purpleTint,
            textMute = c.textMute,
        )
    }
}

/**
 * Seven thin vertical bars, one highlighted pink (most recent), the rest purple.
 * Bar height = run duration when derivable; otherwise scales to `inserted` count
 * (matches SchedulerRun shape). If fewer than 7 runs are available, the empty
 * slots show a short flat stub so the weekday axis stays aligned.
 */
@Composable
private fun BarChart(
    runs: List<SchedulerRun>,
    purple: Color,
    pink: Color,
    track: Color,
    textMute: Color,
) {
    val slots = 7
    // Newest run is the pink highlight; list is oldest-first.
    val highlightIndex = (runs.size - 1).coerceAtLeast(0)
    val values: List<Int> =
        List(slots) { i ->
            val offset = slots - runs.size
            if (i < offset) 0 else runs[i - offset].inserted
        }
    val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val w = size.width
            val h = size.height
            val gap = 10.dp.toPx()
            val barWidth = ((w - gap * (slots - 1)) / slots).coerceAtLeast(1f)
            val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            val minBarPx = 8.dp.toPx()

            values.forEachIndexed { i, v ->
                val normalized = v.toFloat() / maxValue
                val barH = (normalized * h).coerceAtLeast(if (v == 0) 4.dp.toPx() else minBarPx)
                val x = i * (barWidth + gap)
                val y = h - barH
                val highlighted =
                    (i == slots - 1) && runs.isNotEmpty() &&
                        highlightIndex == runs.size - 1
                val color =
                    when {
                        v == 0 -> track
                        highlighted -> pink
                        else -> purple
                    }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = radius,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            weekdayLabels().forEach { label ->
                Text(
                    label,
                    color = textMute,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun weekdayLabels(): List<String> = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
