// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkErrorAndroidTest {
    @Test
    fun `UnknownHostException - airplane mode classifies as Offline`() {
        // Reproduces the exact bug: "Unable to resolve host" when DNS lookup fails.
        val ex = UnknownHostException("Unable to resolve host \"api.podcastindex.org\"")
        assertEquals(NetworkError.Offline, NetworkError.classify(ex))
        assertEquals(NetworkError.OFFLINE_MESSAGE, NetworkError.toUserMessage(ex))
    }

    @Test
    fun `ConnectException classifies as Offline`() {
        val ex = ConnectException("Connection refused")
        assertEquals(NetworkError.Offline, NetworkError.classify(ex))
    }

    @Test
    fun `NoRouteToHostException classifies as Offline`() {
        val ex = NoRouteToHostException("No route")
        assertEquals(NetworkError.Offline, NetworkError.classify(ex))
    }

    @Test
    fun `SocketTimeoutException - actual JVM exception classifies as Timeout`() {
        // Real-world: Ktor wraps the OkHttp socket timeout as java.net.SocketTimeoutException.
        // The Ktor common path checks io.ktor.client.network.sockets.SocketTimeoutException;
        // this verifies the platform-level fallback also catches the JVM exception type.
        val ex = SocketTimeoutException("timeout")
        assertEquals(NetworkError.Timeout, NetworkError.classify(ex))
    }

    @Test
    fun `wrapped UnknownHostException - SDK rethrow as IOException with cause classifies as Offline`() {
        // Defends against PodcastIndexClient (or other libraries) wrapping the connectivity
        // exception in an IOException. Without cause-chain walking this would have classified
        // as Other and surfaced raw text. Pins down the cause-chain traversal in the actual.
        val cause = UnknownHostException("Unable to resolve host")
        val wrapped = IOException("podcast lookup failed", cause)
        assertEquals(NetworkError.Offline, NetworkError.classify(wrapped))
        assertEquals(NetworkError.OFFLINE_MESSAGE, NetworkError.toUserMessage(wrapped))
    }

    @Test
    fun `unrelated IOException without connectivity cause classifies as Other and keeps message`() {
        // Ensures we don't over-classify. A generic IO failure (e.g. EOF) should NOT be
        // treated as offline.
        val ex = IOException("unexpected end of stream")
        val classified = NetworkError.classify(ex)
        assertEquals("unexpected end of stream", NetworkError.toUserMessage(ex))
        // assertEquals on the sealed-type identity to guard against silent reclassification.
        assertEquals(NetworkError.Other(ex), classified)
    }

    @Test
    fun `cause chain depth - deep wrapping still walks down to the connectivity root cause`() {
        // Three-level wrap: outer IOException → middle IOException → root UnknownHostException.
        // Verifies the walker does not stop at depth 1 — guards against an off-by-one regression
        // where someone refactors the loop to only check `this` and `this.cause`.
        val root = UnknownHostException("Unable to resolve host")
        val middle = IOException("middle wrapper", root)
        val outer = IOException("outer wrapper", middle)
        assertEquals(NetworkError.Offline, NetworkError.classify(outer))
    }
}
