// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

actual class PcmDecoder {
    actual suspend fun decodeMono(
        sourceUriOrPath: String,
        startMs: Long,
        endMs: Long,
    ): DecodedPcm = throw NotImplementedError("Snippet rendering is Android-only.")
}
