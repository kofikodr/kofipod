// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback.auto

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [validateArtworkUrl]'s SSRF guard. The exported [ArtworkProvider]
 * fetches whatever URL the caller hands it, gated only by
 * `library.hasArtworkUrl()` — which means an attacker who controls a podcast
 * feed can register `http://192.168.1.1/admin` as an artwork URL and any
 * other app on the device can trigger that fetch via the content URI.
 *
 * These tests cover each side of the validator: scheme, parsing, DNS, and
 * IP-range checks. The InetAddress assertions exercise [isBlockedInetAddress]
 * directly so the per-range rules don't drift even if the validator's
 * resolver/cache wrapping changes.
 */
class ArtworkUrlValidationTest {
    // --- Scheme & URL parsing ---------------------------------------------------

    @Test
    fun rejectsNonHttpScheme() {
        val result = validateArtworkUrl("javascript:alert(1)") { error("resolver must not run") }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("unsupported scheme", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsFileScheme() {
        val result = validateArtworkUrl("file:///etc/passwd") { error("resolver must not run") }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("unsupported scheme", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsBlankHost() {
        val result = validateArtworkUrl("http:///path") { error("resolver must not run") }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("missing host", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsMalformedUrl() {
        val result = validateArtworkUrl("https://[malformed") { error("resolver must not run") }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("malformed url", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsWhenDnsThrows() {
        val result =
            validateArtworkUrl("https://example.invalid/cover.jpg") {
                throw java.net.UnknownHostException("simulated")
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("dns failed", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsWhenResolverReturnsEmpty() {
        val result = validateArtworkUrl("https://example.invalid/cover.jpg") { arrayOf() }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("no addresses", (result as ArtworkUrlCheck.Blocked).reason)
    }

    // --- Address checks via validator (host → resolver → IP ranges) -------------

    @Test
    fun acceptsPublicIpv4Address() {
        // 8.8.8.8 — Google DNS, archetypal public address. If this regresses
        // every artwork on every public podcast feed silently breaks.
        val result =
            validateArtworkUrl("https://cdn.example.com/cover.jpg") {
                arrayOf(InetAddress.getByName("8.8.8.8"))
            }
        assertEquals(ArtworkUrlCheck.Ok, result)
    }

    @Test
    fun rejectsLoopbackIpv4() {
        val result =
            validateArtworkUrl("http://localhost/cover.jpg") {
                arrayOf(InetAddress.getByName("127.0.0.1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsPrivateIpv4_10dot() {
        val result =
            validateArtworkUrl("http://intranet.example/cover.jpg") {
                arrayOf(InetAddress.getByName("10.0.0.1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsPrivateIpv4_192dot168() {
        val result =
            validateArtworkUrl("http://router.local/cover.jpg") {
                arrayOf(InetAddress.getByName("192.168.1.1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsLinkLocalIpv4() {
        val result =
            validateArtworkUrl("http://metadata/cover.jpg") {
                arrayOf(InetAddress.getByName("169.254.169.254"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsCgnatIpv4() {
        // 100.64.0.0/10 — used by ISPs for carrier-grade NAT. Treat as private
        // because customer-premise equipment lives behind it.
        val result =
            validateArtworkUrl("http://cgnat.example/cover.jpg") {
                arrayOf(InetAddress.getByName("100.64.0.1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsIpv6Loopback() {
        val result =
            validateArtworkUrl("http://[::1]/cover.jpg") {
                arrayOf(InetAddress.getByName("::1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsIpv6UniqueLocal_fc00() {
        val result =
            validateArtworkUrl("http://[fc00::1]/cover.jpg") {
                arrayOf(InetAddress.getByName("fc00::1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsIpv4MappedIpv6_privateInside() {
        // ::ffff:192.168.1.1 — IPv4-mapped IPv6 that wraps a private v4 address.
        // Without unwrapping, isSiteLocalAddress wouldn't fire on the v6 form.
        val result =
            validateArtworkUrl("http://wrapped.example/cover.jpg") {
                arrayOf(InetAddress.getByName("::ffff:192.168.1.1"))
            }
        assertTrue(result is ArtworkUrlCheck.Blocked)
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun rejectsMixedPublicAndPrivate_resolverReturnsBoth() {
        // Mixed DNS A-records (a hostname that resolves to both a public IP
        // and a private one) must fail-closed: connecting could pick either.
        val result =
            validateArtworkUrl("https://multi-homed.example/cover.jpg") {
                arrayOf(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("10.0.0.1"),
                )
            }
        assertTrue(result is ArtworkUrlCheck.Blocked, "ANY private address in the set must block")
        assertEquals("private address", (result as ArtworkUrlCheck.Blocked).reason)
    }

    @Test
    fun acceptsAllPublic_multiHomed() {
        // Multi-homed public addresses (e.g. Cloudfront edge node) must still
        // pass — pin the inverse so we don't make multi-A-record CDNs fail.
        val result =
            validateArtworkUrl("https://cdn.example/cover.jpg") {
                arrayOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("8.8.4.4"),
                )
            }
        assertEquals(ArtworkUrlCheck.Ok, result)
    }

    // --- Direct address-range checks (isBlockedInetAddress) ---------------------

    @Test
    fun isBlockedInetAddress_anyLocalV4_blocked() {
        assertTrue(isBlockedInetAddress(InetAddress.getByName("0.0.0.0")))
    }

    @Test
    fun isBlockedInetAddress_0dot8_thisNetwork_blocked() {
        // 0.0.0.0/8 "this network". 0.0.0.0 itself is also matched by
        // isAnyLocalAddress, so we must pin a non-zero address inside the /8
        // to exercise the manual `a == 0` branch — otherwise that branch is
        // dead code from the test suite's perspective.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("0.1.2.3")))
    }

    @Test
    fun isBlockedInetAddress_172_16_private_blocked() {
        // The 172.16/12 range is the middle of the three RFC1918 blocks and
        // must be covered by isSiteLocalAddress.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(isBlockedInetAddress(InetAddress.getByName("172.31.255.254")))
    }

    @Test
    fun isBlockedInetAddress_reservedClassE_blocked() {
        // 240.0.0.0/4 — reserved future use. Real internet hosts never live
        // here. 255.255.255.255 is the broadcast address and also blocked.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("240.0.0.1")))
        assertTrue(isBlockedInetAddress(InetAddress.getByName("255.255.255.255")))
    }

    @Test
    fun isBlockedInetAddress_testNets_blocked() {
        // 192.0.2/24, 198.51.100/24, 203.0.113/24 are documentation-only ranges.
        // Real artwork servers never live in TEST-NETs; if we see one, it's a
        // misconfiguration or attack vector.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("192.0.2.1")))
        assertTrue(isBlockedInetAddress(InetAddress.getByName("198.51.100.1")))
        assertTrue(isBlockedInetAddress(InetAddress.getByName("203.0.113.1")))
    }

    @Test
    fun isBlockedInetAddress_192_0_0_ietfProtocol_blocked() {
        // 192.0.0.0/24 — IETF protocol assignments (DNS64 wellknown prefix
        // 192.0.0.170, etc). Distinct from 192.0.2.0/24 (TEST-NET-1), even
        // though both share the `a == 192 && b == 0` guard. Pin separately so
        // a narrowing refactor that adds `&& c == 2` doesn't silently drop it.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("192.0.0.1")))
    }

    @Test
    fun isBlockedInetAddress_198_18_15_benchmarking_blocked() {
        // 198.18.0.0/15 — benchmarking range per RFC 2544. Both /16s must be
        // blocked. Pin both halves so removing either from the impl trips a
        // test failure.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("198.18.0.1")))
        assertTrue(isBlockedInetAddress(InetAddress.getByName("198.19.255.1")))
    }

    @Test
    fun isBlockedInetAddress_198_51_outsideTestNet2_notBlocked() {
        // 198.51.100.0/24 is TEST-NET-2 — blocked. The rest of 198.51.0.0/16
        // is regular assigned public space. Pin the negative side so an
        // over-broad guard like `b == 51` (no third-octet check) is caught.
        assertTrue(isBlockedInetAddress(InetAddress.getByName("198.51.100.1")))
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("198.51.1.1")))
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("198.51.99.1")))
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("198.51.101.1")))
    }

    @Test
    fun isBlockedInetAddress_publicIpv4_notBlocked() {
        // Sanity: archetypal public addresses must not be blocked. If any of
        // these flips we've over-blocked and silently broken artwork loads.
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("1.1.1.1")))
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("8.8.8.8")))
        assertEquals(false, isBlockedInetAddress(InetAddress.getByName("142.250.190.46")))
    }
}
