// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

// iOS classifier uses message-pattern matching since Ktor Darwin wraps NSError into
// generic Throwables and Kotlin/Native doesn't permit `Throwable as? NSError`. The Ktor
// `HttpRequestTimeoutException` / `ConnectTimeoutException` / `SocketTimeoutException`
// checks in `NetworkError.classify` already catch the structured-timeout case across
// platforms; this fallback handles the Darwin-specific `URLError` text leak.
//
// Patterns include both English `localizedDescription` strings AND the locale-invariant
// numeric NSURLError codes that Apple embeds in the description (e.g. "(NSURLErrorDomain
// error -1009.)"). The numeric codes match regardless of device locale, so users on
// non-English devices still get classified correctly.

// Locale-invariant numeric NSURLError codes (Apple embeds these in the error string,
// e.g. "(NSURLErrorDomain error -1009.)") plus English-locale fallback phrases.
// Mapping: -1009 NotConnectedToInternet, -1003 CannotFindHost, -1004 CannotConnectToHost,
// -1005 NetworkConnectionLost, -1006 DNSLookupFailed, -1018 InternationalRoamingOff,
// -1001 TimedOut.
private val CONNECTIVITY_PATTERNS =
    listOf(
        "error -1009",
        "error -1003",
        "error -1004",
        "error -1005",
        "error -1006",
        "error -1018",
        "nsurlerrordomain",
        "could not connect to the server",
        "the internet connection appears to be offline",
        "a server with the specified hostname could not be found",
        "the network connection was lost",
        "dns lookup failed",
        "international roaming",
    )

private val TIMEOUT_PATTERNS =
    listOf(
        "error -1001",
        "the request timed out",
        "timed out",
    )

internal actual fun Throwable.isPlatformConnectivityError(): Boolean = matchesAnyPattern(CONNECTIVITY_PATTERNS)

internal actual fun Throwable.isPlatformTimeout(): Boolean = matchesAnyPattern(TIMEOUT_PATTERNS)

private fun Throwable.matchesAnyPattern(patterns: List<String>): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        val text = current.message?.lowercase().orEmpty()
        if (patterns.any { it in text }) return true
        current = current.cause
    }
    return false
}
