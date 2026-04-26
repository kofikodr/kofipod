// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.data.repo.DailyListening
import app.kofipod.data.repo.StatsSnapshot
import app.kofipod.data.repo.TopPodcast
import app.kofipod.domain.Tier
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.min

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    viewModel: StatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val tierExplainOpen by viewModel.tierExplainOpen.collectAsState()
    val c = LocalKofipodColors.current

    LaunchedEffect(Unit) { viewModel.markTierSeen() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatsTopBar(onBack = onBack) }

            val snap = state.snapshot
            if (snap == null) {
                item { Spacer(Modifier.height(80.dp)) }
            } else {
                // Single unified scroll. The only state-dependent card is the tier card
                // at the top: a "Brewing…" placeholder while gated, the purple hero once a
                // tier is emitted. Everything below renders the same shape regardless of
                // state — early users just see zeros / empties.
                if (snap.tier == null) {
                    item { BrewingCard(snap = snap) }
                } else {
                    item { TierHeroCard(snap = snap, onTap = viewModel::openTierExplain) }
                }
                item { TotalPlaybackCard(snap = snap) }
                item { DailyPlaybackCard(snap = snap) }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StreakCard(snap = snap, modifier = Modifier.weight(1f))
                        CompletedCard(snap = snap, modifier = Modifier.weight(1f))
                    }
                }
                if (snap.topPodcasts.isNotEmpty()) {
                    item { SectionLabel(title = "Top podcasts", topSpacing = 4.dp) }
                    topPodcastItems(snap.topPodcasts, onOpenPodcast)
                }
            }
        }
    }

    if (tierExplainOpen) {
        TierExplainDialog(
            currentTier = state.snapshot?.tier,
            onDismiss = viewModel::closeTierExplain,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.topPodcastItems(
    list: List<TopPodcast>,
    onOpenPodcast: (String) -> Unit,
) {
    items(list.size) { i ->
        TopPodcastRow(
            entry = list[i],
            rank = i + 1,
            onClick = { onOpenPodcast(list[i].podcastId) },
            showDivider = i < list.lastIndex,
        )
    }
}

@Composable
private fun StatsTopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.border, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 18.dp, strokeWidth = 2f)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "YOUR LISTENING",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Stats",
                color = c.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(40.dp))
    }
}

// ---------- Tier hero (mature) ----------

@Composable
private fun TierHeroCard(
    snap: StatsSnapshot,
    onTap: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val tier = snap.tier ?: return

    val gradient =
        Brush.linearGradient(
            colors = listOf(c.purpleSoft, c.purple, c.purpleDeep),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.lg))
            .background(gradient)
            .clickable(onClick = onTap)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CURRENT TIER · ${tier.rank}/${Tier.entries.size}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                UpdatedTodayPill()
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierGlyph(size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tier.displayName,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "You're averaging ${snap.avgMinPerDayLast30d.toInt()} min/day over the last 30 days.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            TierProgressBar(
                tier = tier,
                avgMinPerDay = snap.avgMinPerDayLast30d,
            )
        }
    }
}

@Composable
private fun UpdatedTodayPill() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .clip(RoundedCornerShape(r.pill))
            .background(c.pink)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "UPDATED TODAY",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TierGlyph(size: androidx.compose.ui.unit.Dp) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(r.md))
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.CoffeeCup, color = Color.White, size = size * 0.6f, strokeWidth = 1.6f)
    }
}

@Composable
private fun TierProgressBar(
    tier: Tier,
    avgMinPerDay: Double,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val next = tier.next
    val (fraction, footer) =
        if (next != null) {
            val remaining = (next.enterMinPerDay - avgMinPerDay).coerceAtLeast(0.0)
            // Span this segment from current tier's enter threshold to next's enter threshold.
            val segStart = tier.enterMinPerDay
            val segEnd = next.enterMinPerDay
            val frac = ((avgMinPerDay - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0)
            frac to "${remaining.toInt()} MIN/DAY TO ${next.displayName.uppercase()}"
        } else {
            1.0 to "TOP TIER REACHED"
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tier.displayName,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (next != null) {
            Text(
                "Next: ${next.displayName} · ${next.enterMinPerDay.toInt()} min/day",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(r.pill))
            .background(Color.White.copy(alpha = 0.2f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.toFloat())
                .height(8.dp)
                .clip(RoundedCornerShape(r.pill))
                .background(c.pink),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        footer,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.0.sp,
        fontFamily = FontFamily.Monospace,
    )
}

// ---------- Brewing (early) ----------

@Composable
private fun BrewingCard(snap: StatsSnapshot) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val daysIn = min(snap.daysSinceFirstListen, Tier.LEVEL_GATE_DAYS).coerceAtLeast(1)
    val remaining = (Tier.LEVEL_GATE_DAYS - daysIn).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.lg))
            .dashedRect(c.borderStrong, r.lg)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrewingGlyph()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "YOUR TIER",
                        color = c.textMute,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Brewing…",
                        color = c.text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        if (remaining > 0) {
                            "Your tier appears after a week of listening. $remaining more day${if (remaining == 1) "" else "s"} to go."
                        } else {
                            "Your tier appears after a week of listening."
                        },
                        color = c.textSoft,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            DayProgressDots(daysIn = daysIn, total = Tier.LEVEL_GATE_DAYS)
            Spacer(Modifier.height(8.dp))
            Text(
                "DAY $daysIn OF ${Tier.LEVEL_GATE_DAYS}",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun BrewingGlyph() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.purpleTint),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.CoffeeCup, color = c.purple, size = 32.dp, strokeWidth = 1.6f)
    }
}

@Composable
private fun DayProgressDots(
    daysIn: Int,
    total: Int,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until total) {
            val filled = i < daysIn
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (filled) c.purple else c.purpleTint),
            )
        }
    }
}

// ---------- Total playback ----------

@Composable
private fun TotalPlaybackCard(snap: StatsSnapshot) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.lg))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.lg))
            .padding(18.dp),
    ) {
        Text(
            "TOTAL PLAYBACK",
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            formatHoursMinutes(snap.totalSecondsAllTime),
            color = c.text,
            fontSize = 38.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SubTotalChip("LAST 7 DAYS", snap.totalSecondsLast7d, Modifier.weight(1f))
            SubTotalChip("LAST 30 DAYS", snap.totalSecondsLast30d, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SubTotalChip(
    label: String,
    seconds: Long,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        modifier
            .clip(RoundedCornerShape(r.md))
            .background(c.bgSubtle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = c.textMute,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            formatHoursMinutes(seconds),
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

// ---------- Daily playback (always shown) ----------

@Composable
private fun DailyPlaybackCard(snap: StatsSnapshot) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val avg = snap.avgMinPerDayLast30d.toInt()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.lg))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.lg))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DAILY PLAYBACK · LAST 30 DAYS",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$avg MIN AVG",
                color = c.textSoft,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(12.dp))
        DailyBars(
            today = snap.today,
            from = snap.windowFromDay,
            data = snap.dailySeriesLast30d,
            barColor = c.purple,
            todayColor = c.pink,
            track = c.purpleTint,
            height = 110.dp,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatShortDate(snap.windowFromDay),
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatShortDate(snap.today - 14),
                color = c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "TODAY",
                color = c.pink,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun DailyBars(
    today: Int,
    from: Int,
    data: List<DailyListening>,
    barColor: Color,
    todayColor: Color,
    track: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    val byDay = data.associateBy { it.epochDay }
    val totalDays = (today - from + 1).coerceAtLeast(1)
    val maxSeconds = (data.maxOfOrNull { it.seconds } ?: 1L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val gap = 3f
        val barWidth = ((size.width - gap * (totalDays - 1)) / totalDays).coerceAtLeast(1f)
        val baseline = size.height
        for (i in 0 until totalDays) {
            val day = from + i
            val seconds = byDay[day]?.seconds ?: 0L
            val ratio = seconds.toDouble() / maxSeconds.toDouble()
            val barH = (size.height * ratio).toFloat().coerceAtLeast(2f).coerceAtMost(size.height)
            val x = i * (barWidth + gap)
            val color =
                if (day == today) {
                    todayColor
                } else if (seconds > 0) {
                    barColor
                } else {
                    track
                }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, baseline - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

// ---------- Streak / Completed cards ----------

@Composable
private fun StreakCard(
    snap: StatsSnapshot,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(
        modifier
            .clip(RoundedCornerShape(r.lg))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.lg))
            .padding(18.dp),
    ) {
        Text(
            "STREAK",
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${snap.currentStreak}",
                color = c.text,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (snap.currentStreak == 1) "day" else "days",
                color = c.textSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Longest · ${snap.longestStreak} ${if (snap.longestStreak == 1) "day" else "days"}",
            color = c.textSoft,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        StreakDots(
            today = snap.today,
            data = snap.dailySeriesLast30d,
            activeColor = c.pink,
            inactiveColor = c.purpleTint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "≥ 5 MIN / DAY",
            color = c.textMute,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StreakDots(
    today: Int,
    data: List<DailyListening>,
    activeColor: Color,
    inactiveColor: Color,
) {
    val byDay = data.associateBy { it.epochDay }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until Tier.ROLLING_WINDOW_DAYS) {
            val day = today - (Tier.ROLLING_WINDOW_DAYS - 1) + i
            val active = (byDay[day]?.seconds ?: 0L) >= Tier.STREAK_MIN_SECONDS_PER_DAY
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(if (active) activeColor else inactiveColor),
            )
        }
    }
}

@Composable
private fun CompletedCard(
    snap: StatsSnapshot,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val ratio =
        if (snap.completedAllTime > 0) {
            (snap.completedLast30d.toDouble() / snap.completedAllTime.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    Column(
        modifier
            .clip(RoundedCornerShape(r.lg))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.lg))
            .padding(18.dp),
    ) {
        Text(
            "COMPLETED",
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${snap.completedAllTime}",
                color = c.text,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "eps",
                color = c.textSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Last 30d · ${snap.completedLast30d} eps",
            color = c.textSoft,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompletedRing(ratio = ratio, size = 36.dp, track = c.purpleTint, fill = c.pink)
            Spacer(Modifier.width(10.dp))
            Text(
                "${(ratio * 100).toInt()}% OF\nALL-TIME",
                color = c.textMute,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp,
            )
        }
    }
}

@Composable
private fun CompletedRing(
    ratio: Double,
    size: androidx.compose.ui.unit.Dp,
    track: Color,
    fill: Color,
) {
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = size.toPx() * 0.18f, cap = StrokeCap.Round)
        val pad = stroke.width / 2f
        val arcSize = Size(this.size.width - pad * 2, this.size.height - pad * 2)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = fill,
            startAngle = -90f,
            sweepAngle = (360f * ratio.toFloat()).coerceAtLeast(if (ratio > 0) 6f else 0f),
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = arcSize,
            style = stroke,
        )
    }
}

// ---------- Top podcasts ----------

@Composable
private fun TopPodcastRow(
    entry: TopPodcast,
    rank: Int,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalKofipodRadii.current.md))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(c.purpleTint),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$rank",
                    color = c.purple,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.podcastTitle.ifBlank { "Untitled podcast" },
                    color = c.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    formatHoursMinutes(entry.seconds),
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
            KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 16.dp)
        }
        if (showDivider) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        }
    }
}

// ---------- Tier explained dialog ----------

@Composable
private fun TierExplainDialog(
    currentTier: Tier?,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.bg)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "How tiers work",
                    color = c.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c.bgSubtle)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    KPIcon(name = KPIconName.Close, color = c.text, size = 14.dp, strokeWidth = 2f)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Your tier is the average minutes per day you listened over the last 30 days. It moves up and down with your habit.",
                color = c.textSoft,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            for (t in Tier.entries) {
                TierExplainRow(tier = t, isCurrent = t == currentTier)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TierExplainRow(
    tier: Tier,
    isCurrent: Boolean,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val bg = if (isCurrent) c.purple else c.surface
    val fg = if (isCurrent) Color.White else c.text
    val sub = if (isCurrent) Color.White.copy(alpha = 0.85f) else c.textMute
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(bg)
            .border(1.dp, if (isCurrent) Color.Transparent else c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tier.rank.toString().padStart(2, '0'),
            color = sub,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(r.sm))
                .background(if (isCurrent) Color.White.copy(alpha = 0.18f) else c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(
                name = KPIconName.CoffeeCup,
                color = if (isCurrent) Color.White else c.purple,
                size = 22.dp,
                strokeWidth = 1.6f,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tier.displayName,
                color = fg,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                tierThresholdLabel(tier),
                color = sub,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        }
        if (isCurrent) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(c.pink)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "YOU",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.0.sp,
                )
            }
        }
    }
}

private fun tierThresholdLabel(tier: Tier): String {
    return if (tier == Tier.Decaf) {
        "> 0 MIN/DAY"
    } else {
        "≥ ${tier.enterMinPerDay.toInt()} MIN/DAY · DROP < ${formatThreshold(tier.dropMinPerDay)}"
    }
}

private fun formatThreshold(d: Double): String {
    val whole = d.toInt()
    if (d == whole.toDouble()) return whole.toString()
    val tenths = ((d - whole) * 10).toInt().coerceIn(0, 9)
    return if (tenths == 0) whole.toString() else "$whole.$tenths"
}

// ---------- formatting helpers ----------

private fun formatHoursMinutes(seconds: Long): String {
    if (seconds <= 0L) return "0m"
    val totalMinutes = seconds / 60L
    val h = totalMinutes / 60L
    val m = totalMinutes % 60L
    return if (h > 0L) {
        if (m == 0L) "${h}h 00m" else "${h}h ${m.toString().padStart(2, '0')}m"
    } else {
        "${m}m"
    }
}

private fun formatShortDate(epochDay: Int): String {
    val d = LocalDate.fromEpochDays(epochDay)
    return "${monthShort(d.month)} ${d.dayOfMonth}"
}

private fun monthShort(month: Month): String =
    when (month) {
        Month.JANUARY -> "Jan"
        Month.FEBRUARY -> "Feb"
        Month.MARCH -> "Mar"
        Month.APRIL -> "Apr"
        Month.MAY -> "May"
        Month.JUNE -> "Jun"
        Month.JULY -> "Jul"
        Month.AUGUST -> "Aug"
        Month.SEPTEMBER -> "Sep"
        Month.OCTOBER -> "Oct"
        Month.NOVEMBER -> "Nov"
        Month.DECEMBER -> "Dec"
    }

// ---------- dashed border modifier ----------

private fun Modifier.dashedRect(
    color: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.5.dp,
    dashLength: androidx.compose.ui.unit.Dp = 6.dp,
    gapLength: androidx.compose.ui.unit.Dp = 5.dp,
): Modifier =
    drawBehind {
        val sp = strokeWidth.toPx()
        val rp = cornerRadius.toPx()
        val stroke =
            Stroke(
                width = sp,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f),
            )
        drawRoundRect(
            color = color,
            topLeft = Offset(sp / 2f, sp / 2f),
            size = Size(size.width - sp, size.height - sp),
            cornerRadius = CornerRadius(rp, rp),
            style = stroke,
        )
    }
