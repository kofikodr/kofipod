// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NetworkErrorTest {
    @Test
    fun `Ktor ConnectTimeoutException classifies as Timeout`() {
        val ex = ConnectTimeoutException("connect timed out after 1000ms")
        assertEquals(NetworkError.Timeout, NetworkError.classify(ex))
    }

    @Test
    fun `Ktor SocketTimeoutException classifies as Timeout`() {
        val ex = SocketTimeoutException("socket timed out after 1000ms")
        assertEquals(NetworkError.Timeout, NetworkError.classify(ex))
    }

    @Test
    fun `unrelated exception with non-blank message classifies as Other carrying that message`() {
        val ex = IllegalStateException("Boom")
        val classified = NetworkError.classify(ex)
        assertIs<NetworkError.Other>(classified)
        assertEquals(ex, classified.original)
        assertEquals("Boom", NetworkError.toUserMessage(ex))
    }

    @Test
    fun `Timeout maps to the user-facing timeout copy via toUserMessage`() {
        val ex = ConnectTimeoutException("connect timed out")
        // Exact-string assertion: this copy is what users see, so wording is part of the contract.
        assertEquals(NetworkError.TIMEOUT_MESSAGE, NetworkError.toUserMessage(ex))
    }

    @Test
    fun `unrelated exception with blank message falls back to provided default`() {
        val ex = RuntimeException("")
        assertEquals("custom-fallback", NetworkError.toUserMessage(ex, fallback = "custom-fallback"))
    }

    @Test
    fun `unrelated exception with null message falls back to provided default`() {
        val ex = RuntimeException()
        assertEquals("custom-fallback", NetworkError.toUserMessage(ex, fallback = "custom-fallback"))
    }

    @Test
    fun `Timeout message ignores fallback so user always sees the curated timeout copy`() {
        // Regression guard: previous raw-message implementation surfaced "Connection timed out"
        // verbatim. The classifier must override fallback for known network categories.
        val ex = ConnectTimeoutException("connect timed out")
        assertEquals(NetworkError.TIMEOUT_MESSAGE, NetworkError.toUserMessage(ex, fallback = "ignored"))
    }
}
