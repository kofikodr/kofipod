// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for [copyWithProgress] — the extracted download copy loop.
 *
 * The point of the extraction was to make the loop *cancellable*. The key case
 * ([stopsWritingWhenCancelled]) fails against a loop without the `ensureActive()`
 * check (it would write every chunk) and passes with it.
 */
class CopyWithProgressTest {
    private val chunkSize = 64 * 1024

    /** InputStream that yields [totalChunks] full buffers, optionally cancelling [job] at read #[cancelAtRead]. */
    private class FakeStream(
        private val totalChunks: Int,
        private val chunkSize: Int,
        private val cancelAtRead: Int = -1,
        private val jobProvider: () -> Job,
    ) : InputStream() {
        private var reads = 0

        override fun read(): Int = throw UnsupportedOperationException("byte-array read only")

        override fun read(b: ByteArray): Int {
            if (reads == cancelAtRead) jobProvider().cancel()
            // minOf keeps the fake self-consistent with InputStream's contract if the
            // SUT's buffer size ever diverges from this test's chunkSize.
            return if (reads++ < totalChunks) minOf(chunkSize, b.size) else -1
        }
    }

    /** A clock that advances [stepMs] on every read, so each iteration crosses the emit gate. */
    private fun advancingClock(stepMs: Long): () -> Long {
        var t = 0L
        return {
            t += stepMs
            t
        }
    }

    @Test
    fun stopsWritingWhenCancelled() =
        runTest {
            val cancelAtRead = 5
            val out = ByteArrayOutputStream()
            val emitted = mutableListOf<DownloadProgress>()
            lateinit var job: Job
            job =
                launch {
                    copyWithProgress(
                        episodeId = "ep1",
                        input = FakeStream(totalChunks = 100, chunkSize = chunkSize, cancelAtRead = cancelAtRead) { job },
                        output = out,
                        startOffset = 0,
                        total = 100L * chunkSize,
                        // emit every surviving iteration
                        now = advancingClock(300L),
                        emit = { emitted += it },
                    )
                }
            job.join()

            assertTrue(job.isCancelled, "job should end cancelled")
            // Reads 0..4 are written + emitted; the read that triggers cancel (read #5) returns
            // bytes but ensureActive() throws before its write/emit, so exactly 5 chunks land
            // and no progress is reported after the cancel point.
            assertEquals(cancelAtRead * chunkSize, out.size())
            assertEquals(cancelAtRead, emitted.size, "no progress should be emitted after cancel")
            assertTrue(emitted.all { it.state == DownloadProgress.State.Downloading })
        }

    @Test
    fun doesNotWriteOrEmitWhenCancelledBeforeFirstChunk() =
        runTest {
            val out = ByteArrayOutputStream()
            val emitted = mutableListOf<DownloadProgress>()
            lateinit var job: Job
            job =
                launch {
                    copyWithProgress(
                        episodeId = "ep1",
                        input = FakeStream(totalChunks = 100, chunkSize = chunkSize, cancelAtRead = 0) { job },
                        output = out,
                        startOffset = 0,
                        total = 100L * chunkSize,
                        // would cross the gate if we ever reached emit
                        now = { 1000L },
                        emit = { emitted += it },
                    )
                }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(0, out.size(), "no bytes should be written after a pre-write cancel")
            assertTrue(emitted.isEmpty(), "no progress should be emitted after a pre-write cancel")
        }

    @Test
    fun copiesAllBytesAndEmitsDownloadingProgress() =
        runTest {
            val source = ByteArray(200_000) { (it % 256).toByte() }
            val out = ByteArrayOutputStream()
            val emitted = mutableListOf<DownloadProgress>()

            copyWithProgress(
                episodeId = "ep1",
                input = ByteArrayInputStream(source),
                output = out,
                startOffset = 0,
                total = source.size.toLong(),
                now = advancingClock(300L),
                emit = { emitted += it },
            )

            assertTrue(source.contentEquals(out.toByteArray()), "all bytes copied verbatim")
            assertTrue(emitted.isNotEmpty(), "progress should be emitted on the golden path")
            assertTrue(emitted.all { it.state == DownloadProgress.State.Downloading })
            assertEquals(source.size.toLong(), emitted.last().downloadedBytes)
        }

    @Test
    fun throttlesEmissionsWhenChunksArriveWithinTheInterval() =
        runTest {
            val chunks = 10
            val emitted = mutableListOf<DownloadProgress>()

            // Clock advances only 50ms per read — well under the 200ms emit gate — so most
            // iterations are suppressed even though all chunks are written.
            copyWithProgress(
                episodeId = "ep1",
                input = FakeStream(totalChunks = chunks, chunkSize = chunkSize) { Job() },
                output = ByteArrayOutputStream(),
                startOffset = 0,
                total = chunks.toLong() * chunkSize,
                now = advancingClock(50L),
                emit = { emitted += it },
            )

            assertTrue(
                emitted.size in 1 until chunks,
                "throttle should emit at least once but far fewer than $chunks times, got ${emitted.size}",
            )
        }

    @Test
    fun reportsReceivedAsTotalWhenContentLengthUnknown() =
        runTest {
            val source = ByteArray(150_000) { 7 }
            val emitted = mutableListOf<DownloadProgress>()

            // total = -1L models a response with no Content-Length (common for podcast feeds).
            copyWithProgress(
                episodeId = "ep1",
                input = ByteArrayInputStream(source),
                output = ByteArrayOutputStream(),
                startOffset = 0,
                total = -1L,
                now = advancingClock(300L),
                emit = { emitted += it },
            )

            assertTrue(emitted.isNotEmpty())
            assertTrue(
                emitted.all { it.totalBytes == it.downloadedBytes },
                "unknown total should be coerced up to bytes received",
            )
        }

    @Test
    fun reportsProgressRelativeToStartOffsetForResumedDownloads() =
        runTest {
            val resumeFrom = 1_000_000L
            val source = ByteArray(150_000) { 7 }
            val emitted = mutableListOf<DownloadProgress>()

            copyWithProgress(
                episodeId = "ep1",
                input = ByteArrayInputStream(source),
                output = ByteArrayOutputStream(),
                startOffset = resumeFrom,
                // partial-on-disk prefix + remaining body
                total = resumeFrom + source.size,
                now = advancingClock(300L),
                emit = { emitted += it },
            )

            // Progress is cumulative: the first emission already includes the resumed prefix
            // plus one buffer read, and the last equals prefix + full body.
            assertEquals(resumeFrom + chunkSize, emitted.first().downloadedBytes)
            assertEquals(resumeFrom + source.size, emitted.last().downloadedBytes)
        }
}
