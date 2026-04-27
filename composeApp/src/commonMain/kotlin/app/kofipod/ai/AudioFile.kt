// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.utils.io.ByteReadChannel

/**
 * Opens a downloaded audio file at [path] as a streaming Ktor channel for the
 * Files API resumable upload. Implementations must be lazy — they should NOT
 * read the file into memory up-front (a 200MB episode would OOM the JVM).
 *
 * Android wraps `java.io.File.readChannel()`. iOS is stubbed (audio fallback
 * is Android-first); call sites guard with [audioFallbackSupported] so the
 * stub is never reached on a happy path.
 */
internal expect fun openLocalFileChannel(path: String): ByteReadChannel

/**
 * Whether this platform's [openLocalFileChannel] is real. iOS returns false
 * until we ship an NSFileHandle-backed channel; Android always returns true.
 */
internal expect fun audioFallbackSupported(): Boolean
