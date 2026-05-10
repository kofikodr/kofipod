// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.utils.io.ByteReadChannel

// iOS audio fallback is not yet wired — call sites must guard with
// [audioFallbackSupported] before reaching this stub.
internal actual fun openFileRange(
    path: String,
    offset: Long,
    length: Long,
): ByteReadChannel = error("Audio summary is not yet implemented on iOS — use the transcript path.")

internal actual fun audioFallbackSupported(): Boolean = false
