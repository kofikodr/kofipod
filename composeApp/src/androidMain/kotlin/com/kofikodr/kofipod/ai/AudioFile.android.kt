// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.File

// Ktor's `File.readChannel(start, endInclusive)` already seeks via
// RandomAccessFile under the hood and streams lazily, so we don't load the
// whole file into memory. The endInclusive offset is `offset + length - 1`
// (Ktor uses an inclusive upper bound). A zero-length range yields an empty
// channel, which is what the resumable-upload `query` PUT expects when we
// just want to read the server's current offset.
internal actual fun openFileRange(
    path: String,
    offset: Long,
    length: Long,
): ByteReadChannel =
    if (length <= 0L) {
        ByteReadChannel.Empty
    } else {
        File(path).readChannel(start = offset, endInclusive = offset + length - 1)
    }

internal actual fun audioFallbackSupported(): Boolean = true
