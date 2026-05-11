// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.kofikodr.kofipod.ui.screens.scheduler.DayBucket
import com.kofikodr.kofipod.ui.screens.scheduler.KindAwareChart
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [KindAwareChart] — the "Last 7 days" bar chart on the
 * Scheduler Details screen. The chart's bucketing is unit-tested in
 * `SchedulerChartBucketingTest`; here we lock the visual rendering.
 *
 * Fixtures cover the four slot states the chart must distinguish:
 *  - episode-check only (left mini-bar at full slot width)
 *  - backup only (right mini-bar at full slot width, fixed height)
 *  - both kinds on the same day (two side-by-side mini-bars)
 *  - empty day (muted track stub)
 *
 * Plus an all-empty baseline to pin "no runs yet" rendering.
 */
class SchedulerChartSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun schedulerChart_mixedRuns_phone_light() {
        paparazzi.snapshot {
            ChartHarness(buckets = MIXED_FIXTURE)
        }
    }

    @Test
    fun schedulerChart_empty_phone_light() {
        paparazzi.snapshot {
            ChartHarness(buckets = EMPTY_FIXTURE)
        }
    }

    @Test
    fun schedulerChart_backupHeavy_phone_light() {
        paparazzi.snapshot {
            ChartHarness(buckets = BACKUP_HEAVY_FIXTURE)
        }
    }
}

@Composable
private fun ChartHarness(buckets: List<DayBucket>) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxWidth().background(c.bg).padding(20.dp)) {
            Box(
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
            }
        }
    }
}

// 2026-05-06 .. 2026-05-12 inclusive — matches the SCHEDULER_CHART_DAYS window.
private val WINDOW = (0..6).map { i -> LocalDate(2026, 5, 6 + i) }

// Exercises every slot variant: episode-only (Wed), backup-only (Thu),
// both kinds (Sat + today), empty (Tue + Fri). Inserted values vary so the
// height-scaling shows up visually.
private val MIXED_FIXTURE: List<DayBucket> =
    listOf(
        DayBucket(WINDOW[0], episodeInserted = 2, hasBackup = false),
        DayBucket(WINDOW[1], episodeInserted = null, hasBackup = false),
        DayBucket(WINDOW[2], episodeInserted = 8, hasBackup = false),
        DayBucket(WINDOW[3], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[4], episodeInserted = null, hasBackup = false),
        DayBucket(WINDOW[5], episodeInserted = 5, hasBackup = true),
        DayBucket(WINDOW[6], episodeInserted = 3, hasBackup = true),
    )

private val EMPTY_FIXTURE: List<DayBucket> =
    WINDOW.map { DayBucket(it, episodeInserted = null, hasBackup = false) }

// Heavy backup activity, sparse episode-checks. Pins the "backup as a thin right
// mini-bar" case so a regression that shrinks/widens the backup marker shows up.
private val BACKUP_HEAVY_FIXTURE: List<DayBucket> =
    listOf(
        DayBucket(WINDOW[0], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[1], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[2], episodeInserted = 4, hasBackup = true),
        DayBucket(WINDOW[3], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[4], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[5], episodeInserted = null, hasBackup = true),
        DayBucket(WINDOW[6], episodeInserted = null, hasBackup = true),
    )
