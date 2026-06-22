// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback.auto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard on [buildArtworkHttpClient]'s security-critical wiring.
 *
 * The SSRF fix (issue #31) depends on two non-negotiable client settings:
 *  - the DNS resolver is [SsrfBlockingDns] (resolve-once + block-private, the
 *    authoritative TOCTOU gate), and
 *  - redirect-following is disabled (a 3xx must not bounce the fetch to an
 *    unvalidated host).
 *
 * These are easy to drop in a refactor with every other test still green —
 * which is exactly why they're pinned here.
 */
class ArtworkHttpClientWiringTest {
    @Test
    fun `artwork client pins the SSRF-blocking DNS resolver`() {
        val client = buildArtworkHttpClient()
        assertTrue(
            client.dns is SsrfBlockingDns,
            "ArtworkProvider's OkHttpClient must use SsrfBlockingDns — without it the " +
                "DNS-rebinding TOCTOU window (issue #31) reopens.",
        )
    }

    @Test
    fun `artwork client disables redirect following`() {
        val client = buildArtworkHttpClient()
        assertFalse(client.followRedirects, "HTTP redirects must be disabled (3xx → private IP).")
        assertFalse(client.followSslRedirects, "HTTPS redirects must be disabled (3xx → private IP).")
    }
}
