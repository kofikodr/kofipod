// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.repo

import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {
    @Test
    fun streamCacheCapBytesNow_roundTripsPersistedValue() {
        val db = inMemoryDatabase()
        val repo = SettingsRepository(db)

        // Default value before any write should be the 512 MB fallback.
        assertEquals(
            SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES,
            repo.streamCacheCapBytesNow(),
            "expected default fallback of 512 MB when no value is persisted",
        )
        assertEquals(512L * 1024 * 1024, SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES)

        val newCap = 256L * 1024 * 1024
        repo.setStreamCacheCapBytes(newCap)

        assertEquals(
            newCap,
            repo.streamCacheCapBytesNow(),
            "streamCacheCapBytesNow should reflect the last persisted value",
        )
    }

    @Test
    fun dailyCheckEnabledNow_defaultsTrue_whenNothingPersisted() {
        // Crux of issue #2: with nothing persisted (a fresh install) the toggle is ON,
        // so the cold-start scheduler must see `true` and schedule the episode-check worker.
        val repo = SettingsRepository(inMemoryDatabase())
        assertEquals(
            true,
            repo.dailyCheckEnabledNow(),
            "daily check must default ON so a fresh install schedules the episode-check worker",
        )
    }

    @Test
    fun dailyCheckEnabledNow_roundTripsPersistedValue() {
        val repo = SettingsRepository(inMemoryDatabase())

        repo.setDailyCheckEnabled(false)
        assertEquals(false, repo.dailyCheckEnabledNow(), "must reflect a persisted false")

        repo.setDailyCheckEnabled(true)
        assertEquals(true, repo.dailyCheckEnabledNow(), "must reflect a persisted true")
    }
}
