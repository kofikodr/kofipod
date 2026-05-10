// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NetworkErrorHandlerTest {
    @Test
    fun `timeout with cached data emits transient snackbar and suppresses inline error`() =
        runTest {
            val bus = UiEventBus()
            val handler = NetworkErrorHandler(bus)
            val collected = async { bus.events.first() }
            yield()

            val ex = ConnectTimeoutException("connect timed out")
            val result = handler.handle(ex, hasCachedData = true)

            assertNull(result, "Inline error must be suppressed when cached data exists")
            assertEquals(
                UiEvent.Snackbar(NetworkError.TIMEOUT_MESSAGE),
                collected.await(),
                "Snackbar must carry the curated timeout copy",
            )
        }

    @Test
    fun `timeout without cached data returns the friendly inline message and emits no snackbar`() =
        runTest {
            val bus = UiEventBus()
            val handler = NetworkErrorHandler(bus)
            // Sentinel collector: if the handler erroneously emits to the bus this Deferred
            // completes. The assertion is sound because UiEventBus.emit() calls tryEmit()
            // synchronously — when handle() returns, any emission has already happened, and
            // any non-emission is permanent (no later async path can fire).
            val emitted = async { bus.events.first() }
            yield()

            val ex = ConnectTimeoutException("connect timed out")
            val result = handler.handle(ex, hasCachedData = false)

            assertEquals(NetworkError.TIMEOUT_MESSAGE, result)
            assertEquals(false, emitted.isCompleted, "No snackbar should be emitted for empty-state path")
            emitted.cancel()
        }

    @Test
    fun `non-network exception with non-blank message returns its raw message regardless of cache flag`() =
        runTest {
            val bus = UiEventBus()
            val handler = NetworkErrorHandler(bus)
            val emitted = async { bus.events.first() }
            yield()

            val ex = IllegalStateException("DB write failed")
            val result = handler.handle(ex, hasCachedData = true)

            assertEquals("DB write failed", result, "Non-network errors must surface their original message")
            assertEquals(false, emitted.isCompleted, "Non-network errors must not trigger the offline snackbar")
            emitted.cancel()
        }

    @Test
    fun `non-network exception with null message returns the supplied fallback`() =
        runTest {
            val bus = UiEventBus()
            val handler = NetworkErrorHandler(bus)

            val ex = RuntimeException()
            val result = handler.handle(ex, hasCachedData = false, fallback = "Failed to refresh")

            assertEquals("Failed to refresh", result)
        }

    @Test
    fun `CancellationException is rethrown as the same type so coroutine cancellation propagates correctly`() =
        runTest {
            val bus = UiEventBus()
            val handler = NetworkErrorHandler(bus)

            val ex = CancellationException("scope cancelled")
            // assertFailsWith pins the exception TYPE, not just "something threw" — this prevents
            // a regression where a future change to handle() throws e.g. RuntimeException from
            // passing this test silently.
            val thrown =
                assertFailsWith<CancellationException> {
                    handler.handle(ex, hasCachedData = true)
                }
            assertEquals("scope cancelled", thrown.message)
        }
}
