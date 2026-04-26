// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.kofipod.db.KofipodDatabase
import app.kofipod.domain.Tier
import app.kofipod.util.todayEpochDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext

/** Per-day listening total, in seconds. */
data class DailyListening(val epochDay: Int, val seconds: Long)

data class TopPodcast(val podcastId: String, val podcastTitle: String, val seconds: Long)

/**
 * Snapshot of every stat the screen needs. Computed in one combine() so the UI gets
 * an atomic update — relevant because the level depends on rolling-30d totals that
 * are also surfaced as their own stat (avg min/day) and tier hysteresis state.
 */
data class StatsSnapshot(
    val totalSecondsAllTime: Long,
    val totalSecondsLast7d: Long,
    val totalSecondsLast30d: Long,
    val avgMinPerDayLast30d: Double,
    val dailySeriesLast30d: List<DailyListening>,
    val topPodcasts: List<TopPodcast>,
    val completedAllTime: Long,
    val completedLast30d: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    val daysSinceFirstListen: Int,
    val today: Int,
    /** First epoch day in the rolling 30-day window (today − 29). Single source of truth for the bar chart. */
    val windowFromDay: Int,
    val tier: Tier?,
    val tierBeforeThisCompute: Tier?,
)

class StatsRepository(
    private val db: KofipodDatabase,
    private val settings: SettingsRepository,
    private val flowContext: CoroutineContext = Dispatchers.Default,
) {
    private fun dailyTotalsFlow(
        fromDay: Int,
        toDay: Int,
    ): Flow<List<DailyListening>> =
        db.listeningSessionQueries.selectDailyTotalsBetween(fromDay.toLong(), toDay.toLong())
            .asFlow()
            .mapToList(flowContext)
            .map { rows ->
                rows.map { DailyListening(it.epochDay.toInt(), it.seconds ?: 0L) }
            }

    private fun totalSinceFlow(fromDay: Int): Flow<Long> =
        db.listeningSessionQueries.selectTotalSince(fromDay.toLong())
            .asFlow()
            .mapToOne(flowContext)

    private fun totalAllTimeFlow(): Flow<Long> =
        db.listeningSessionQueries.selectTotalAllTime()
            .asFlow()
            .mapToOne(flowContext)

    private fun topPodcastsFlow(maxRows: Long = 5L): Flow<List<TopPodcast>> =
        db.listeningSessionQueries.selectTopPodcasts(maxRows)
            .asFlow()
            .mapToList(flowContext)
            .map { rows ->
                rows.map {
                    TopPodcast(
                        podcastId = it.podcastId,
                        podcastTitle = it.podcastTitle,
                        seconds = it.seconds ?: 0L,
                    )
                }
            }

    private fun completedAllTimeFlow(): Flow<Long> =
        db.playbackStateQueries.selectCompletedAllTime()
            .asFlow()
            .mapToOne(flowContext)

    private fun completedSinceFlow(sinceMs: Long): Flow<Long> =
        db.playbackStateQueries.selectCompletedSince(sinceMs)
            .asFlow()
            .mapToOne(flowContext)

    private fun firstDayFlow(): Flow<Int?> =
        db.listeningSessionQueries.selectFirstDay()
            .asFlow()
            .mapToOne(flowContext)
            .map { it.firstDay?.toInt() }

    /**
     * Emits the current local epoch day at subscription, then re-emits whenever the day
     * rolls over (checked once a minute). Lets [snapshot] re-issue date-bucketed queries
     * at midnight without forcing the screen to be recomposed/restarted manually.
     */
    private fun todayFlow(): Flow<Int> =
        flow {
            var current = todayEpochDay()
            emit(current)
            while (currentCoroutineContext().isActive) {
                delay(DAY_ROLLOVER_POLL_MS)
                val next = todayEpochDay()
                if (next != current) {
                    current = next
                    emit(next)
                }
            }
        }

    /**
     * Single flow combining every stat needed by the screen. The outer [flatMapLatest]
     * over [todayFlow] re-builds the inner combine when the local day rolls over, so a
     * screen left open across midnight refreshes naturally. [distinctUntilChanged]
     * suppresses identical re-emissions on the high-frequency persist ticks.
     */
    @Suppress("OPT_IN_USAGE")
    fun snapshot(): Flow<StatsSnapshot> =
        todayFlow()
            .flatMapLatest { today -> snapshotForDay(today) }
            .distinctUntilChanged()

    private fun snapshotForDay(today: Int): Flow<StatsSnapshot> {
        val from30 = today - (Tier.ROLLING_WINDOW_DAYS - 1)
        val from7 = today - 6
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val ms30dAgo = nowMs - (Tier.ROLLING_WINDOW_DAYS.toLong() * MS_PER_DAY)
        val tierEmittedFlow =
            settings.metaFlowPublic(SettingsRepository.KEY_STATS_TIER_EMITTED)
                .map { it?.toIntOrNull()?.let { rank -> Tier.entries.firstOrNull { t -> t.rank == rank } } }

        // Nested combines because Flow.combine() is typed up to 5 sources only. Group A
        // covers the listening-session aggregates, Group B covers completion + first-day
        // + tier-state.
        data class GroupA(
            val daily: List<DailyListening>,
            val total7: Long,
            val total30: Long,
            val totalAll: Long,
            val top: List<TopPodcast>,
        )

        data class GroupB(
            val completedAll: Long,
            val completed30: Long,
            val firstDay: Int?,
            val previousTier: Tier?,
        )
        val groupA =
            combine(
                dailyTotalsFlow(from30, today),
                totalSinceFlow(from7),
                totalSinceFlow(from30),
                totalAllTimeFlow(),
                topPodcastsFlow(),
            ) { daily, total7, total30, totalAll, top -> GroupA(daily, total7, total30, totalAll, top) }
        val groupB =
            combine(
                completedAllTimeFlow(),
                completedSinceFlow(ms30dAgo),
                firstDayFlow(),
                tierEmittedFlow,
            ) { completedAll, completed30, firstDay, previousTier ->
                GroupB(completedAll, completed30, firstDay, previousTier)
            }

        return combine(groupA, groupB) { a, b ->
            val daily = a.daily
            val total7 = a.total7
            val total30 = a.total30
            val totalAll = a.totalAll
            val top = a.top
            val completedAll = b.completedAll
            val completed30 = b.completed30
            val firstDay = b.firstDay
            val previousTier = b.previousTier

            // daysSinceFirst counts ELAPSED calendar days inclusive: a user who first
            // listened today reads as day 1. The 7-day gate (LEVEL_GATE_DAYS) is therefore
            // "tier appears one week after first session," not "after 7 days of activity."
            // Lenient by design — matches the spec phrasing "appears after a week."
            val daysSinceFirst = firstDay?.let { (today - it) + 1 } ?: 0
            // Divide by the days the user has actually been around, not always 30. Using a
            // straight 30-day denominator would penalise new users in days 7..29, who could
            // otherwise have qualified for a higher tier given their actual cadence.
            val effectiveDenominator = daysSinceFirst.coerceIn(1, Tier.ROLLING_WINDOW_DAYS).toDouble()
            val avgMinPerDay = total30.toDouble() / 60.0 / effectiveDenominator

            // Streaks: only computed off the active days array. The "current streak"
            // is the run of consecutive days ending today (or yesterday — counting
            // yesterday lets the user not lose the streak by checking before listening).
            val activeDays =
                daily.filter { it.seconds >= Tier.STREAK_MIN_SECONDS_PER_DAY }
                    .map { it.epochDay }
                    .toSet()
            val (currentStreak, longestStreak) = computeStreaks(activeDays, today)

            // Tier: only meaningful once the user has at least LEVEL_GATE_DAYS of usage
            // history. Before then, expose null and the UI shows the "Brewing…" placeholder.
            val tier: Tier? =
                if (daysSinceFirst >= Tier.LEVEL_GATE_DAYS) {
                    Tier.computeTier(avgMinPerDay, previousTier)
                } else {
                    null
                }

            StatsSnapshot(
                totalSecondsAllTime = totalAll,
                totalSecondsLast7d = total7,
                totalSecondsLast30d = total30,
                avgMinPerDayLast30d = avgMinPerDay,
                dailySeriesLast30d = daily,
                topPodcasts = top,
                completedAllTime = completedAll,
                completedLast30d = completed30,
                currentStreak = currentStreak,
                longestStreak = longestStreak,
                daysSinceFirstListen = daysSinceFirst,
                today = today,
                windowFromDay = from30,
                tier = tier,
                tierBeforeThisCompute = previousTier,
            )
        }
    }

    private companion object {
        // Day-rollover poll cadence. One minute is a fine compromise between battery and
        // perceived latency at midnight; the screen is rarely open across midnight anyway.
        const val DAY_ROLLOVER_POLL_MS = 60_000L
        const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }

    /**
     * Persist the current tier so a subsequent computation can apply hysteresis. Called
     * by the ViewModel once it has a non-null tier in its emitted state. Idempotent.
     */
    fun persistEmittedTier(tier: Tier) {
        val current = settings.getMetaNow(SettingsRepository.KEY_STATS_TIER_EMITTED)?.toIntOrNull()
        if (current != tier.rank) {
            settings.put(SettingsRepository.KEY_STATS_TIER_EMITTED, tier.rank.toString())
        }
    }

    /** Snapshot — for use in the UI badge logic. */
    fun emittedTierNow(): Tier? =
        settings.getMetaNow(SettingsRepository.KEY_STATS_TIER_EMITTED)
            ?.toIntOrNull()
            ?.let { rank -> Tier.entries.firstOrNull { it.rank == rank } }

    fun seenTierNow(): Tier? =
        settings.getMetaNow(SettingsRepository.KEY_STATS_TIER_SEEN)
            ?.toIntOrNull()
            ?.let { rank -> Tier.entries.firstOrNull { it.rank == rank } }

    /**
     * Reactive flow combining emitted vs seen — true when the user has an unseen change.
     *
     * Sentinel-free: a missing `KEY_STATS_TIER_SEEN` simply means "the user has never
     * opened the Stats screen since a tier was emitted." Combined with `emitted != null`,
     * that's exactly the "first-ever tier emission" case we want to show the dot for.
     */
    fun hasUnseenTierChange(): Flow<Boolean> =
        combine(
            settings.metaFlowPublic(SettingsRepository.KEY_STATS_TIER_EMITTED),
            settings.metaFlowPublic(SettingsRepository.KEY_STATS_TIER_SEEN),
        ) { emitted, seen ->
            val e = emitted?.toIntOrNull()
            val s = seen?.toIntOrNull()
            e != null && e != s
        }

    fun markTierSeen(tier: Tier) {
        settings.put(SettingsRepository.KEY_STATS_TIER_SEEN, tier.rank.toString())
    }
}

private fun computeStreaks(
    activeDays: Set<Int>,
    today: Int,
): Pair<Int, Int> {
    if (activeDays.isEmpty()) return 0 to 0

    // Longest streak across the window.
    val sorted = activeDays.sorted()
    var longest = 1
    var run = 1
    for (i in 1 until sorted.size) {
        if (sorted[i] == sorted[i - 1] + 1) {
            run++
            if (run > longest) longest = run
        } else {
            run = 1
        }
    }

    // Current streak: walk back from today (or yesterday, if today isn't yet active).
    val anchor =
        when {
            today in activeDays -> today
            (today - 1) in activeDays -> today - 1
            else -> return 0 to longest
        }
    var current = 0
    var cursor = anchor
    while (cursor in activeDays) {
        current++
        cursor--
    }
    return current to longest
}
