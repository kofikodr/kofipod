// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import com.kofikodr.kofipod.data.repo.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Kinds of scheduled work the user can see in the "Last 7 runs" chart. Wire value is a
 * lowercase string so older rows that pre-date this field decode to [EpisodeCheck] via
 * the default and the format stays forward-compatible.
 */
enum class SchedulerRunKind(val wire: String) {
    EpisodeCheck("episode_check"),
    Backup("backup"),
    ;

    companion object {
        fun fromWire(value: String?): SchedulerRunKind = entries.firstOrNull { it.wire == value } ?: EpisodeCheck
    }
}

/**
 * One entry in the rolling run log. [inserted] and [shows] only have meaning for
 * [SchedulerRunKind.EpisodeCheck] runs — backup runs persist them as 0. The [kind]
 * field has a default for backward compat: rows written before this field existed
 * decode as [SchedulerRunKind.EpisodeCheck].
 */
@Serializable
data class SchedulerRun(
    val at: Long,
    val inserted: Int,
    val shows: Int,
    val kind: String = SchedulerRunKind.EpisodeCheck.wire,
) {
    val runKind: SchedulerRunKind get() = SchedulerRunKind.fromWire(kind)
}

object SchedulerRunLog {
    private const val MAX_ENTRIES = 14
    private val json = Json { ignoreUnknownKeys = true }

    private val listSerializer = ListSerializer(SchedulerRun.serializer())

    fun read(settings: SettingsRepository): List<SchedulerRun> {
        val raw = settings.getMetaNow(SettingsRepository.KEY_SCHEDULER_RUNS) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    fun append(
        settings: SettingsRepository,
        run: SchedulerRun,
    ) {
        val current = read(settings)
        val updated = (current + run).takeLast(MAX_ENTRIES)
        settings.put(
            SettingsRepository.KEY_SCHEDULER_RUNS,
            json.encodeToString(listSerializer, updated),
        )
    }

    /** Convenience for backup runs — sets `inserted=0`, `shows=0`, and the right kind. */
    fun appendBackup(
        settings: SettingsRepository,
        atMs: Long,
    ) = append(
        settings,
        SchedulerRun(at = atMs, inserted = 0, shows = 0, kind = SchedulerRunKind.Backup.wire),
    )
}
