// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.scheduler

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
import androidx.compose.runtime.remember
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
import com.kofikodr.kofipod.background.SchedulerRun
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
                onToggle = viewModel::setDailyCheckEnabled,
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

            SectionLabel("Last 7 days", topSpacing = 20.dp)
            LastRunsChart(runs = state.runs)

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
    onToggle: (Boolean) -> Unit,
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
            // Toggles `KEY_DAILY_CHECK` via the VM. This flag gates both the episode-
            // check worker AND the SAF auto-backup worker, so flipping it off here
            // pauses every automated scheduled task; manual actions in Settings still
            // work. The setter also calls Scheduler.enable/disable so WorkManager
            // doesn't sit on a registered-but-suppressed periodic worker.
            onCheckedChange = onToggle,
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
 * Render a UTC epoch-ms timestamp as `HH:MM` in the device's local time zone.
 * Run-log entries are written with `Clock.System.now().toEpochMilliseconds()`
 * (UTC), so the display must convert before formatting — otherwise users on
 * any non-UTC offset see the wrong hour.
 */
private fun formatTimeOfDay(epochMs: Long): String {
    val local =
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
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
    // Cache zone + today per `runs` identity. The chart re-runs on every recompose
    // triggered by playback state etc.; the calendar date doesn't change within
    // a screen visit, so there is no reason to re-read the system clock each frame.
    val (today, zone) =
        remember(runs) {
            val z = TimeZone.currentSystemDefault()
            Clock.System.now().toLocalDateTime(z).date to z
        }
    val buckets = remember(runs, today, zone) { bucketRunsByLocalDay(runs, today, zone) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        KindAwareChart(
            buckets = buckets,
            episodeCheck = c.purple,
            backup = c.pink,
            track = c.purpleTint,
            textMute = c.textMute,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            LegendDot(color = c.purple)
            Spacer(Modifier.width(6.dp))
            Text(
                "Daily check",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(14.dp))
            LegendDot(color = c.pink)
            Spacer(Modifier.width(6.dp))
            Text(
                "Backup",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * One slot per calendar day in the trailing [SCHEDULER_CHART_DAYS] window, oldest on
 * the left and today on the right. Within each slot we draw up to two mini-bars
 * side-by-side:
 *  - episode-check bar (left half), height scaled to the window's max daily inserted;
 *  - backup bar (right half), fixed-height presence marker.
 *
 * Single-kind days get a centred mini-bar instead of two; empty days show a muted
 * track stub. Weekday labels are derived from each bucket's real date.
 */
@Composable
private fun KindAwareChart(
    buckets: List<DayBucket>,
    episodeCheck: Color,
    backup: Color,
    track: Color,
    textMute: Color,
) {
    val slots = buckets.size
    val maxInserted =
        buckets.mapNotNull { it.episodeInserted }.maxOrNull()?.coerceAtLeast(1) ?: 1

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            val w = size.width
            val h = size.height
            val gap = 10.dp.toPx()
            val slotWidth = ((w - gap * (slots - 1)) / slots).coerceAtLeast(1f)
            val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val minBarPx = 8.dp.toPx()
            val backupHeightPx = (h * BACKUP_BAR_HEIGHT_RATIO).coerceAtLeast(minBarPx)
            val miniGap = 3.dp.toPx()
            val pairWidth = ((slotWidth - miniGap) / 2f).coerceAtLeast(1f)

            buckets.forEachIndexed { i, bucket ->
                val slotLeft = i * (slotWidth + gap)
                val hasEpisode = bucket.episodeInserted != null
                val hasBackup = bucket.hasBackup
                if (!hasEpisode && !hasBackup) {
                    drawRoundRect(
                        color = track,
                        topLeft = Offset(slotLeft, h - 4.dp.toPx()),
                        size = Size(slotWidth, 4.dp.toPx()),
                        cornerRadius = radius,
                    )
                    return@forEachIndexed
                }
                if (hasEpisode) {
                    val normalized = bucket.episodeInserted!!.toFloat() / maxInserted
                    val barH = (normalized * h).coerceAtLeast(minBarPx)
                    val barW = if (hasBackup) pairWidth else slotWidth
                    drawRoundRect(
                        color = episodeCheck,
                        topLeft = Offset(slotLeft, h - barH),
                        size = Size(barW, barH),
                        cornerRadius = radius,
                    )
                }
                if (hasBackup) {
                    val barW = if (hasEpisode) pairWidth else slotWidth
                    val barLeft = if (hasEpisode) slotLeft + pairWidth + miniGap else slotLeft
                    drawRoundRect(
                        color = backup,
                        topLeft = Offset(barLeft, h - backupHeightPx),
                        size = Size(barW, backupHeightPx),
                        cornerRadius = radius,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            buckets.forEach { bucket ->
                Text(
                    weekdayLetter(bucket.date),
                    color = textMute,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private const val BACKUP_BAR_HEIGHT_RATIO = 0.45f
