// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.nav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [DeepLinks]'s cold-start delivery contract: an emission made before
 * any collector exists MUST be delivered to the first collector that
 * subscribes. The prior `MutableSharedFlow(replay = 0)` failed this and
 * silently dropped cold-start notification taps because MainActivity emits
 * before `setContent { AppShell() }` installs the collectors.
 *
 * The new Channel-based implementation buffers a single value per event
 * type with DROP_OLDEST. These tests pin both halves: pre-collect emit
 * survives, AND a stale value is not re-delivered to a second collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinksTest {
    // DeepLinks is a singleton object — its channels survive across tests.
    // Drain any pending values between tests so a leftover emit from one
    // test doesn't make the next one's "wait for value" assertion succeed
    // for the wrong reason.
    @AfterTest
    fun drainAllChannels() =
        runTest {
            drain { DeepLinks.openPlayer.first() }
            drain { DeepLinks.openEpisode.first() }
            drain { DeepLinks.openLibrary.first() }
            drain { DeepLinks.openSettings.first() }
        }

    @Test
    fun emitBeforeCollectIsBuffered_andDeliveredToFirstCollector() =
        runTest {
            // MainActivity's "handleIntent" pattern: emit happens during
            // app init, before any compose collector exists.
            DeepLinks.requestOpenPlayer()

            // Collector subscribes after the emit. Must still receive the value.
            val received =
                withTimeoutOrNull(1_000) {
                    DeepLinks.openPlayer.first()
                }
            assertEquals(Unit, received, "Pre-collect emission must be buffered and delivered on first collect")
        }

    @Test
    fun emitBeforeCollect_episodeIdPayloadDelivered() =
        runTest {
            DeepLinks.requestOpenEpisode("episode-42")

            val received =
                withTimeoutOrNull(1_000) {
                    DeepLinks.openEpisode.first()
                }
            assertEquals("episode-42", received, "Episode-id payload must survive the pre-collect window")
        }

    @Test
    fun secondCollectorDoesNotReplayStaleValue() =
        runTest {
            DeepLinks.requestOpenLibrary()
            // First collector consumes the buffered value.
            DeepLinks.openLibrary.first()

            // A re-mounted AppShell collects again. Must NOT receive the
            // already-consumed value — the audit explicitly warned about
            // replay-style flows causing repeated navigation.
            val replayed =
                withTimeoutOrNull(200) {
                    DeepLinks.openLibrary.first()
                }
            assertNull(replayed, "Already-consumed value must not replay to a later collector")
        }

    @Test
    fun rapidDoubleEmit_keepsTheNewestValue_DROP_OLDEST() =
        runTest {
            // Buffer capacity = 1. If two emits race a single collector,
            // DROP_OLDEST means the newer wins. For deep links the newest
            // user intent is the right one to surface.
            DeepLinks.requestOpenEpisode("first")
            DeepLinks.requestOpenEpisode("second")

            val received =
                withTimeoutOrNull(1_000) {
                    DeepLinks.openEpisode.first()
                }
            assertEquals("second", received, "Newer emission must replace older when both arrive before a collector")
        }

    @Test
    fun postCollectEmitIsAlsoDelivered() =
        runTest {
            // The "warm-app" path: notification arrives while AppShell is
            // already running. Must still work, not just the cold-start case.
            // backgroundScope is the test-API "outlive me" scope — coroutines
            // there are cancelled on test exit so the test doesn't hang.
            val received = kotlinx.coroutines.CompletableDeferred<Unit?>()
            backgroundScope.launch {
                received.complete(withTimeoutOrNull(1_000) { DeepLinks.openSettings.first() })
            }
            // Yield once so the collector is parked on receive() before we emit.
            testScheduler.runCurrent()

            DeepLinks.requestOpenSettings()
            assertEquals(Unit, received.await())
        }

    private suspend fun drain(receive: suspend () -> Any?) {
        // Best-effort drain: if a value is pending, swallow it. If not,
        // the timeout short-circuits.
        withTimeoutOrNull(50) { receive() }
    }
}
