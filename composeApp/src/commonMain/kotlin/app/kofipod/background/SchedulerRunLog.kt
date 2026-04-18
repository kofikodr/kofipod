// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import app.kofipod.data.repo.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SchedulerRun(val at: Long, val inserted: Int, val shows: Int)

object SchedulerRunLog {
    private const val MAX_ENTRIES = 14
    private val json = Json { ignoreUnknownKeys = true }

    private val listSerializer = ListSerializer(SchedulerRun.serializer())

    fun read(settings: SettingsRepository): List<SchedulerRun> {
        val raw = settings.getMetaNow(SettingsRepository.KEY_SCHEDULER_RUNS) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    fun append(settings: SettingsRepository, run: SchedulerRun) {
        val current = read(settings)
        val updated = (current + run).takeLast(MAX_ENTRIES)
        settings.put(
            SettingsRepository.KEY_SCHEDULER_RUNS,
            json.encodeToString(listSerializer, updated),
        )
    }
}
