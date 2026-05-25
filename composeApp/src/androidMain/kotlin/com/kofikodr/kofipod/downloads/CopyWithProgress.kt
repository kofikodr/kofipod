// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

/**
 * Pumps [input] into [output] in 64 KiB chunks, emitting throttled [DownloadProgress]
 * updates via [emit].
 *
 * Extracted out of [DownloadService] so the copy loop can be exercised in a plain
 * coroutine test (mirrors the [resumePlan] extraction). The two non-obvious bits both
 * exist so that cancelling the enclosing coroutine actually stops the transfer:
 *
 * - [currentCoroutineContext].ensureActive() runs every iteration. Coroutine
 *   cancellation is cooperative, and the only other suspension point here
 *   (`SharedFlow.emit` inside [emit]) takes a non-suspending fast path that does not
 *   observe cancellation — so without this check the loop would run to EOF even after
 *   `Job.cancel()`. The check is placed *before* the write so the post-cancel chunk is
 *   never flushed to disk.
 * - The caller is responsible for aborting a *stalled* [input] read (e.g. via
 *   `Call.cancel()`); `ensureActive()` only fires while bytes are flowing.
 *
 * @param total best-effort total size for progress UI; coerced up to [received] when
 *   unknown (-1) or smaller than what we've already read.
 * @param now injectable clock so the 200 ms emit throttle is testable.
 */
internal suspend fun copyWithProgress(
    episodeId: String,
    input: InputStream,
    output: OutputStream,
    startOffset: Long,
    total: Long,
    now: () -> Long = { System.currentTimeMillis() },
    emit: suspend (DownloadProgress) -> Unit,
) {
    val buf = ByteArray(64 * 1024)
    var read: Int
    var received = startOffset
    var lastEmit = 0L
    while (input.read(buf).also { read = it } > 0) {
        currentCoroutineContext().ensureActive()
        output.write(buf, 0, read)
        received += read
        val t = now()
        if (t - lastEmit > EMIT_INTERVAL_MS) {
            emit(
                DownloadProgress(
                    episodeId = episodeId,
                    downloadedBytes = received,
                    totalBytes = total.coerceAtLeast(received),
                    state = DownloadProgress.State.Downloading,
                ),
            )
            lastEmit = t
        }
    }
}

private const val EMIT_INTERVAL_MS = 200L
