// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the on-wire shape of the scheduler run log. The Scheduler Details screen renders
 * a unified chart for both episode-check and SAF-backup runs; the `kind` discriminator
 * is what makes that possible. Two invariants are load-bearing:
 *  1. Rows written before the `kind` field existed (pre-Slice-N) decode as
 *     [SchedulerRunKind.EpisodeCheck] — the default keeps the format
 *     forward-compatible without a SyncMeta migration.
 *  2. Backup runs carry kind="backup" and `inserted=0` so the chart's
 *     scale-by-insertion logic doesn't crash on missing magnitude.
 */
class SchedulerRunLogTest {
    @Test
    fun appendBackup_tagsKindAsBackup() {
        val settings = SettingsRepository(inMemoryDatabase())

        SchedulerRunLog.appendBackup(settings, atMs = 1_700_000_000_000L)

        val runs = SchedulerRunLog.read(settings)
        assertEquals(1, runs.size)
        val run = runs.single()
        assertEquals(SchedulerRunKind.Backup, run.runKind)
        assertEquals(0, run.inserted, "backup runs persist with insertion count of zero")
        assertEquals(0, run.shows)
        assertEquals(1_700_000_000_000L, run.at)
    }

    @Test
    fun read_decodesLegacyRows_asEpisodeCheck() {
        // A row stored by a build that pre-dates the `kind` field must decode without
        // failure — the default value picks up the missing field and the row is tagged
        // EpisodeCheck. Pinned via raw JSON rather than through `append` so we can't
        // regress the default by silently re-encoding.
        val settings = SettingsRepository(inMemoryDatabase())
        val legacyJson = """[{"at":1700000000000,"inserted":3,"shows":2}]"""
        settings.put(SettingsRepository.KEY_SCHEDULER_RUNS, legacyJson)

        val runs = SchedulerRunLog.read(settings)
        assertEquals(1, runs.size)
        assertEquals(SchedulerRunKind.EpisodeCheck, runs.single().runKind)
        assertEquals(3, runs.single().inserted)
    }

    @Test
    fun append_rollsOff_pastMaxEntries() {
        val settings = SettingsRepository(inMemoryDatabase())

        // 20 episode-check runs interleaved with 5 backup runs.
        repeat(20) { i ->
            SchedulerRunLog.append(
                settings,
                SchedulerRun(at = 1000L + i, inserted = i, shows = 1),
            )
        }
        repeat(5) { i ->
            SchedulerRunLog.appendBackup(settings, atMs = 9000L + i)
        }

        val runs = SchedulerRunLog.read(settings)
        // MAX_ENTRIES = 14; we wrote 25.
        assertEquals(14, runs.size)
        // Oldest entries fell off the head — newest must remain.
        assertEquals(9004L, runs.last().at, "newest entry is the last appended backup")
        assertTrue(runs.last().runKind == SchedulerRunKind.Backup)
    }

    @Test
    fun roundTrip_preservesKind_acrossSerialization() {
        // Belt-and-suspenders: serialise + read back through the actual settings store.
        val settings = SettingsRepository(inMemoryDatabase())
        SchedulerRunLog.append(
            settings,
            SchedulerRun(at = 1L, inserted = 0, shows = 0, kind = SchedulerRunKind.Backup.wire),
        )
        SchedulerRunLog.append(
            settings,
            SchedulerRun(at = 2L, inserted = 5, shows = 2, kind = SchedulerRunKind.EpisodeCheck.wire),
        )

        val raw = settings.getMetaNow(SettingsRepository.KEY_SCHEDULER_RUNS)!!
        // Sanity: the on-wire encoding actually contains the kind tag.
        assertTrue("\"kind\":\"backup\"" in raw, "wire payload carries backup kind: $raw")

        val parsed =
            Json { ignoreUnknownKeys = true }
                .decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(SchedulerRun.serializer()),
                    raw,
                )
        assertEquals(SchedulerRunKind.Backup, parsed.first().runKind)
        assertEquals(SchedulerRunKind.EpisodeCheck, parsed.last().runKind)
    }
}
