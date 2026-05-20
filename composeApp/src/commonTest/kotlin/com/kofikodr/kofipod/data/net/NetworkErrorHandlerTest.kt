// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [NetworkErrorHandler]'s post-decoupling contract. The handler no
 * longer owns a [com.kofikodr.kofipod.ui.UiEventBus] reference — snackbar
 * emission is delegated to a caller-supplied `emitSnackbar` lambda, so
 * the data layer doesn't import the UI layer.
 *
 * Tests capture the snackbar emission via a simple `mutableListOf` rather
 * than spinning up a UiEventBus and a flow collector. The contract is the
 * same: timeout/offline + cached → snackbar fires + inline-null; otherwise
 * inline message returned + no snackbar.
 */
class NetworkErrorHandlerTest {
    @Test
    fun timeoutWithCachedData_emitsTransientSnackbar_andSuppressesInlineError() =
        runTest {
            val handler = NetworkErrorHandler()
            val emitted = mutableListOf<String>()

            val ex = ConnectTimeoutException("connect timed out")
            val result = handler.handle(ex, hasCachedData = true, emitSnackbar = { emitted += it })

            assertNull(result, "Inline error must be suppressed when cached data exists")
            assertEquals(
                listOf(NetworkError.TIMEOUT_MESSAGE),
                emitted,
                "Snackbar must carry the curated timeout copy",
            )
        }

    @Test
    fun timeoutWithoutCachedData_returnsFriendlyInlineMessage_andEmitsNoSnackbar() =
        runTest {
            val handler = NetworkErrorHandler()
            val emitted = mutableListOf<String>()

            val ex = ConnectTimeoutException("connect timed out")
            val result = handler.handle(ex, hasCachedData = false, emitSnackbar = { emitted += it })

            assertEquals(NetworkError.TIMEOUT_MESSAGE, result)
            assertTrue(emitted.isEmpty(), "No snackbar must be emitted for empty-state path")
        }

    @Test
    fun nonNetworkExceptionWithNonBlankMessage_returnsRawMessage_regardlessOfCacheFlag() =
        runTest {
            val handler = NetworkErrorHandler()
            val emitted = mutableListOf<String>()

            val ex = IllegalStateException("DB write failed")
            val result = handler.handle(ex, hasCachedData = true, emitSnackbar = { emitted += it })

            assertEquals("DB write failed", result, "Non-network errors must surface their original message")
            assertTrue(emitted.isEmpty(), "Non-network errors must not trigger the offline snackbar")
        }

    @Test
    fun nonNetworkExceptionWithNullMessage_returnsSuppliedFallback() =
        runTest {
            val handler = NetworkErrorHandler()

            val ex = RuntimeException()
            val result = handler.handle(ex, hasCachedData = false, fallback = "Failed to refresh")

            assertEquals("Failed to refresh", result)
        }

    @Test
    fun cancellationException_isRethrownAsSameType_soCoroutineCancellationPropagates() =
        runTest {
            val handler = NetworkErrorHandler()

            val ex = CancellationException("scope cancelled")
            val thrown =
                assertFailsWith<CancellationException> {
                    handler.handle(ex, hasCachedData = true, emitSnackbar = { /* must not run */ })
                }
            assertEquals("scope cancelled", thrown.message)
        }

    @Test
    fun snackbarPath_worksWithoutEmitCallback() =
        runTest {
            // Callers that don't care about snackbar emission can omit the
            // lambda — `hasCachedData = true + offline` still returns null
            // (so the screen suppresses its empty-state), the snackbar is
            // simply dropped on the floor. Pins that the callback is
            // optional, not required.
            val handler = NetworkErrorHandler()

            val ex = ConnectTimeoutException("connect timed out")
            val result = handler.handle(ex, hasCachedData = true)
            assertNull(result)
        }
}
