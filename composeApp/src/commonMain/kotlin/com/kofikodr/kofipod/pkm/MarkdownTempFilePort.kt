// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

/**
 * Writes a `.md` blob to a platform-specific cache path and returns the
 * absolute path. Caller is responsible for sharing or otherwise consuming the
 * file. Files placed here are subject to OS cache eviction; do not assume
 * persistence.
 */
expect class MarkdownTempFilePort {
    suspend fun writeTemp(
        filename: String,
        content: String,
    ): String
}
