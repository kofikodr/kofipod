// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.SettingsRepository
import app.kofipod.testing.inMemoryDatabase
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
}
