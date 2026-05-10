// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.crypto

/**
 * iOS does not currently consume sha256Hex (reviewer-unlock is Android-only,
 * and SettingsScreen is the only call site). Returns an empty string so the
 * length-prefix check inside [com.kofikodr.kofipod.crypto.constantTimeHexEquals]
 * trivially rejects every input — no crash, no bypass.
 *
 * If an iOS consumer ever arrives that needs a real digest, replace with
 * CommonCrypto's CC_SHA256.
 */
actual fun sha256Hex(input: String): String = ""
