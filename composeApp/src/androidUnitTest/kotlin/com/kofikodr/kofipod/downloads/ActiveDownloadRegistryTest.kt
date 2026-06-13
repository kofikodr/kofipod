// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActiveDownloadRegistryTest {
    @Test
    fun firstEpisodeRegistersEmitsQueuedAndStartsJob() {
        val active = ConcurrentHashMap<String, Job>()
        val job = Job()
        var queuedEmissions = 0
        val started = mutableListOf<Job>()

        assertTrue(
            startActiveDownloadIfIdle(
                active = active,
                episodeId = "ep1",
                job = job,
                onQueued = { queuedEmissions++ },
                startJob = { started += it },
            ),
        )

        assertSame(job, active["ep1"])
        assertEquals(1, queuedEmissions)
        assertEquals(1, started.size)
        assertSame(job, started.single())

        job.cancel()
    }

    @Test
    fun duplicateEpisodeKeepsOriginalJobAndNeverStartsReplacement() {
        val active = ConcurrentHashMap<String, Job>()
        val original = Job()
        val replacement = Job()
        var queuedEmissions = 0
        val started = mutableListOf<Job>()

        assertTrue(
            startActiveDownloadIfIdle(
                active = active,
                episodeId = "ep1",
                job = original,
                onQueued = { queuedEmissions++ },
                startJob = { started += it },
            ),
        )
        assertFalse(
            startActiveDownloadIfIdle(
                active = active,
                episodeId = "ep1",
                job = replacement,
                onQueued = { queuedEmissions++ },
                startJob = { started += it },
            ),
        )

        assertSame(original, active["ep1"])
        assertEquals(1, queuedEmissions, "duplicate enqueue must not emit a second Queued event")
        assertEquals(1, started.size, "duplicate enqueue must not start a second writer")
        assertSame(original, started.single(), "only the original writer should start")
        assertFalse(original.isCancelled, "duplicate enqueue must not cancel the active writer")
        assertTrue(replacement.isCancelled, "unused replacement job must not remain attached to the service scope")

        original.cancel()
    }
}
