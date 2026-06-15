// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PodcastIndexClientProviderTest {
    private class FixedEffective(var creds: PodcastIndexCreds) {
        fun asResolve(): suspend () -> PodcastIndexCreds = { creds }
    }

    // PodcastIndexClient is a final class (not an interface), so we use a type-param provider
    // with Any as the client type in tests, verifying identity and build-count behaviour.
    private fun fakeClient(): Any = object : Any() {}

    @Test
    fun buildsOnce_andReusesWhenCredsUnchanged() =
        runTest {
            var builds = 0
            val holder = FixedEffective(PodcastIndexCreds("k", "s"))
            val provider =
                PodcastIndexClientProvider(resolve = holder.asResolve(), build = {
                    builds++
                    fakeClient()
                })
            val a = provider.get()
            val b = provider.get()
            assertSame(a, b)
            assertEquals(1, builds)
        }

    @Test
    fun rebuildsOnceWhenCredsChange() =
        runTest {
            var builds = 0
            val holder = FixedEffective(PodcastIndexCreds("k1", "s1"))
            val provider =
                PodcastIndexClientProvider(resolve = holder.asResolve(), build = {
                    builds++
                    fakeClient()
                })
            provider.get()
            holder.creds = PodcastIndexCreds("k2", "s2")
            provider.get()
            provider.get()
            assertEquals(2, builds, "one initial build + one rebuild on the creds change")
        }

    @Test
    fun concurrentGets_doNotDoubleBuild() =
        runTest {
            var builds = 0
            val holder = FixedEffective(PodcastIndexCreds("k", "s"))
            val provider =
                PodcastIndexClientProvider(resolve = holder.asResolve(), build = {
                    builds++
                    fakeClient()
                })
            (1..8).map { async { provider.get() } }.awaitAll()
            assertEquals(1, builds)
        }
}
