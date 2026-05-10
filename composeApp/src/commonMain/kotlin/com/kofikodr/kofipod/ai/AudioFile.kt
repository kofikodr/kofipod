// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.utils.io.ByteReadChannel

/**
 * Opens a single byte range `[offset, offset + length)` of the file at [path]
 * as a streaming Ktor channel. Backs the Files API chunked-resume protocol —
 * each chunk PUT re-reads its own slice, and a resume after a dropped
 * connection re-opens the slice starting at the server-confirmed offset.
 *
 * Implementations must seek lazily; reading the entire file just to skip to
 * [offset] would defeat the chunking. Behaviour for offsets past EOF or
 * negative length is undefined — callers compute both from the upload's
 * declared `sizeBytes`, so out-of-range values would indicate a bug above
 * this seam, not a runtime case to handle gracefully.
 *
 * iOS is stubbed (audio fallback is Android-first); call sites guard with
 * [audioFallbackSupported] so the stub is never reached on a happy path.
 */
internal expect fun openFileRange(
    path: String,
    offset: Long,
    length: Long,
): ByteReadChannel

/**
 * Whether this platform's [openFileRange] is real. iOS returns false until we
 * ship an NSFileHandle-backed range opener; Android always returns true.
 */
internal expect fun audioFallbackSupported(): Boolean
