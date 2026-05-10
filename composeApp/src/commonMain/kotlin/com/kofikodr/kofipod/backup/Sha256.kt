// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

/**
 * Returns a 64-character lowercase hex SHA-256 digest of [bytes]. Used by
 * [BackupRepository] to stamp a checksum into the manifest at backup time and to verify
 * the bundled DB hasn't been tampered with at restore time.
 *
 * `expect` because there's no kotlin-stdlib SHA-256 — Android uses
 * `java.security.MessageDigest`. The iOS actual throws because the SAF backup feature
 * isn't surfaced on iOS in v1; if we ever wire it up there, swap to `CC_SHA256`.
 */
expect fun sha256(bytes: ByteArray): String
