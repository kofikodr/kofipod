// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastIndexConfigRepositoryTest {
    private class FakeStore(var stored: PodcastIndexCreds? = null, val failSet: Boolean = false) :
        PodcastIndexCredentialStore {
        override suspend fun get(): PodcastIndexCreds? = stored

        override suspend fun set(creds: PodcastIndexCreds) {
            if (failSet) error("disk full")
            stored = creds
        }

        override suspend fun clear() {
            stored = null
        }
    }

    @Test
    fun hydratesConfiguredTrue_whenStoreHasUsableCreds() =
        runTest {
            val repo =
                PodcastIndexConfigRepository(
                    FakeStore(PodcastIndexCreds("k", "s")),
                    CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )
            advanceUntilIdle()
            assertTrue(repo.isConfigured().value)
        }

    @Test
    fun hydratesConfiguredFalse_whenStoreEmpty() =
        runTest {
            val repo = PodcastIndexConfigRepository(FakeStore(null), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            advanceUntilIdle()
            assertFalse(repo.isConfigured().value)
        }

    @Test
    fun setCredentials_persistsAndFlipsFlag() =
        runTest {
            val store = FakeStore(null)
            val repo = PodcastIndexConfigRepository(store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            advanceUntilIdle()
            repo.setCredentials(PodcastIndexCreds("k", "s"))
            assertTrue(repo.isConfigured().value)
            assertEquals(PodcastIndexCreds("k", "s"), repo.currentCreds())
        }

    @Test
    fun setCredentials_doesNotFlipFlag_whenStoreWriteFails() =
        runTest {
            val repo =
                PodcastIndexConfigRepository(FakeStore(null, failSet = true), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            advanceUntilIdle()
            runCatching { repo.setCredentials(PodcastIndexCreds("k", "s")) }
            assertFalse(repo.isConfigured().value, "flag must stay false when the encrypted write fails")
        }

    @Test
    fun disconnect_clearsStoreAndFlag() =
        runTest {
            val store = FakeStore(PodcastIndexCreds("k", "s"))
            val repo = PodcastIndexConfigRepository(store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            advanceUntilIdle()
            repo.disconnect()
            assertFalse(repo.isConfigured().value)
            assertNull(repo.currentCreds())
        }
}
