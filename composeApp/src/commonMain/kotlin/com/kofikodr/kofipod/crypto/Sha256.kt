// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.crypto

/**
 * Returns the SHA-256 of [input] (UTF-8 bytes) as lower-case hex.
 *
 * Used by the reviewer-unlock flow: the binary embeds only the hash of the
 * unlock code (via BuildKonfig), so the plaintext code is not recoverable
 * from the APK / source. iOS actual is currently a stub — the reviewer-unlock
 * affordance is Android-only because Play Store review is the only consumer.
 */
expect fun sha256Hex(input: String): String

/**
 * Constant-time hex-string equality for hash comparisons. Avoids the
 * (theoretical) timing-side-channel that a short-circuiting `==` would expose
 * when comparing user-supplied input against an embedded hash. Compares
 * case-insensitively because hex hashes are interchangeable in either case.
 */
fun constantTimeHexEquals(
    a: String,
    b: String,
): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        val ca = a[i].lowercaseChar().code
        val cb = b[i].lowercaseChar().code
        diff = diff or (ca xor cb)
    }
    return diff == 0
}
