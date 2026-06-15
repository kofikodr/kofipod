// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EffectivePodcastIndexCredentialsTest {
    private class FakeStore(var stored: PodcastIndexCreds?) : PodcastIndexCredentialStore {
        override suspend fun get(): PodcastIndexCreds? = stored

        override suspend fun set(creds: PodcastIndexCreds) {
            stored = creds
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private fun config(
        stored: PodcastIndexCreds?,
        scope: CoroutineScope,
    ) = PodcastIndexConfigRepository(FakeStore(stored), scope)

    @Test
    fun returnsBuildTime_whenNoByok() =
        runTest {
            val effective =
                EffectivePodcastIndexCredentials(
                    config = config(null, CoroutineScope(UnconfinedTestDispatcher(testScheduler))),
                    buildTime = PodcastIndexCreds("build-key", "build-secret"),
                )
            advanceUntilIdle()
            assertEquals(PodcastIndexCreds("build-key", "build-secret"), effective.resolve())
        }

    @Test
    fun returnsByok_whenConfigured() =
        runTest {
            val effective =
                EffectivePodcastIndexCredentials(
                    config = config(PodcastIndexCreds("user-key", "user-secret"), CoroutineScope(UnconfinedTestDispatcher(testScheduler))),
                    buildTime = PodcastIndexCreds("build-key", "build-secret"),
                )
            advanceUntilIdle()
            assertEquals(PodcastIndexCreds("user-key", "user-secret"), effective.resolve())
        }

    @Test
    fun returnsBuildTime_whenByokHalfFilled() =
        runTest {
            val effective =
                EffectivePodcastIndexCredentials(
                    config = config(PodcastIndexCreds("user-key", ""), CoroutineScope(UnconfinedTestDispatcher(testScheduler))),
                    buildTime = PodcastIndexCreds("build-key", "build-secret"),
                )
            advanceUntilIdle()
            assertEquals(PodcastIndexCreds("build-key", "build-secret"), effective.resolve())
        }
}
