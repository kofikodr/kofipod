// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback.auto

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/**
 * SSRF mitigation for [ArtworkProvider]. The provider is exported, so any
 * other app on the device can resolve a `content://…/artwork/<encoded-url>`
 * URI we know about (because the URL was already published in a podcast feed
 * we subscribe to). Without validation the provider would happily fetch
 * `http://localhost/admin` or `http://192.168.1.1/router-status` and return
 * the bytes through `openFile()` — turning Kofipod into a confused-deputy
 * SSRF proxy.
 *
 * The **authoritative** SSRF gate is `SsrfBlockingDns` (below), pinned onto the
 * provider's `OkHttpClient`: it resolves the host once, blocks if any resolved
 * address is private, and OkHttp then connects to exactly those addresses — so
 * there is no validate-then-reconnect DNS-rebinding window (issue #31).
 * [validateArtworkUrl] in this file is a **non-authoritative** pre-flight: it
 * rejects obviously-bad URLs (bad scheme, missing host, an already-private
 * resolution) cheaply before a request is built, but it does its own DNS lookup
 * and is therefore not the layer that closes the TOCTOU.
 *
 * The layers, in order of authority:
 *  - `SsrfBlockingDns` — resolve-once + block-private + connect-to-validated (authoritative).
 *  - Provider's fetch caps the response size, rejects non-image content-types,
 *    and disables redirect-following so a 3xx → private IP can't sneak past
 *    (caller in [ArtworkProvider]).
 *  - [validateArtworkUrl] pre-flight: scheme + host + a private-address early-out
 *    (this file; cheap belt-and-braces, not the TOCTOU gate).
 *
 * `usesCleartextTraffic="false"` in the manifest blocks `http://` at the
 * platform layer too; the scheme check here is a belt for the suspenders.
 */
internal sealed interface ArtworkUrlCheck {
    data object Ok : ArtworkUrlCheck

    data class Blocked(val reason: String) : ArtworkUrlCheck
}

internal const val MAX_ARTWORK_BYTES: Long = 8L * 1024L * 1024L
internal const val ARTWORK_BUFFER_SIZE: Int = 8 * 1024

/**
 * Returns true if [address] is in any range that must NOT be reachable from
 * the device's external-network surface: loopback (127/8, ::1), link-local
 * (169.254/16, fe80::/10), site-local / private (10/8, 172.16/12, 192.168/16,
 * fc00::/7), multicast, any-local (0.0.0.0, ::), CGNAT (100.64/10), broadcast
 * 255.255.255.255, and reserved blocks (240/4, IPv4-mapped private IPv6, etc).
 */
internal fun isBlockedInetAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress) return true
    if (address.isLoopbackAddress) return true
    if (address.isLinkLocalAddress) return true
    if (address.isSiteLocalAddress) return true
    if (address.isMulticastAddress) return true
    return when (address) {
        is Inet4Address -> isBlockedIpv4(address.address)
        is Inet6Address -> isBlockedIpv6(address.address)
        else -> false
    }
}

private fun isBlockedIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val a = bytes[0].toInt() and 0xFF
    val b = bytes[1].toInt() and 0xFF
    // 0.0.0.0/8 — "this network" (also caught by isAnyLocal for 0.0.0.0/32)
    if (a == 0) return true
    // 100.64.0.0/10 — CGNAT
    if (a == 100 && (b and 0xC0) == 0x40) return true
    // 192.0.0.0/24 IETF protocol, 192.0.2.0/24 TEST-NET-1
    if (a == 192 && b == 0) return true
    // 198.18.0.0/15 benchmarking
    if (a == 198 && (b == 18 || b == 19)) return true
    // 198.51.100.0/24 TEST-NET-2 — exact /24 only; the rest of 198.51/16 is
    // assigned public space and must not be over-blocked (CDN edges).
    if (a == 198 && b == 51 && (bytes[2].toInt() and 0xFF) == 100) return true
    // 203.0.113.0/24 TEST-NET-3
    if (a == 203 && b == 0) return true
    // 240.0.0.0/4 reserved (incl. 255.255.255.255 broadcast)
    if (a >= 240) return true
    return false
}

private fun isBlockedIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return false
    // IPv4-mapped IPv6 (::ffff:0:0/96): unwrap and re-check
    val isV4Mapped =
        bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    if (isV4Mapped) return isBlockedIpv4(bytes.copyOfRange(12, 16))
    // Unique-local fc00::/7 (covers fc and fd prefixes)
    if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
    return false
}

/**
 * Validates [url] is safe to fetch from the exported [ArtworkProvider]: must
 * be http(s), must parse, must resolve to at least one address, and every
 * resolved address must be public.
 *
 * Pure-function shape with an injectable [resolver] so JVM unit tests can
 * stub DNS without going through `InetAddress.getAllByName`.
 */
internal fun validateArtworkUrl(
    url: String,
    resolver: (String) -> Array<InetAddress>,
): ArtworkUrlCheck {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return ArtworkUrlCheck.Blocked("unsupported scheme")
    }
    val host =
        try {
            URL(url).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return ArtworkUrlCheck.Blocked("malformed url")
        } ?: return ArtworkUrlCheck.Blocked("missing host")

    val addresses =
        try {
            resolver(host)
        } catch (_: Exception) {
            return ArtworkUrlCheck.Blocked("dns failed")
        }
    if (addresses.isEmpty()) return ArtworkUrlCheck.Blocked("no addresses")
    if (addresses.any { isBlockedInetAddress(it) }) {
        return ArtworkUrlCheck.Blocked("private address")
    }
    return ArtworkUrlCheck.Ok
}

/**
 * The authoritative SSRF gate for [ArtworkProvider]'s network fetch (issue #31).
 *
 * The old code validated the URL's resolved addresses and then let
 * `URL(url).openConnection()` resolve the host *again* — a classic
 * validate-then-fetch TOCTOU (DNS rebinding) window: the second resolution
 * could return a private address the first never saw. Routing the fetch
 * through an [okhttp3.OkHttpClient] configured with this [Dns] closes that
 * window, because OkHttp connects to *exactly* the addresses returned here —
 * there is no independent re-resolution between the check and the connect.
 *
 * [lookup] resolves via [systemDns] (injectable for tests), then fail-closes
 * if the host has no addresses or if *any* resolved address is non-public
 * (mirrors [validateArtworkUrl]'s "ANY private address blocks" rule, since
 * OkHttp may connect to any address in the returned list).
 */
internal class SsrfBlockingDns(private val systemDns: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = systemDns.lookup(hostname)
        if (resolved.isEmpty()) throw UnknownHostException("no addresses for $hostname")
        if (resolved.any { isBlockedInetAddress(it) }) {
            throw UnknownHostException("blocked non-public address for $hostname")
        }
        return resolved
    }
}
