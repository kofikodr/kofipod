// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Behaviour of [forEachIsolatingFailures] — the per-feed isolation that keeps
 * one bad feed from aborting the whole `EpisodeCheckWorker` run (issue #27).
 */
class ForEachIsolatingFailuresTest {
    @Test
    fun processesEveryItemWhenNoneFail() =
        runTest {
            val visited = mutableListOf<Int>()

            val outcome = forEachIsolatingFailures(listOf(1, 2, 3)) { visited += it }

            assertEquals(listOf(1, 2, 3), visited, "every item must be processed")
            assertEquals(IsolatedSweepOutcome(succeeded = 3, failed = 0), outcome)
        }

    @Test
    fun continuesPastAFailingItemAndCountsIt() =
        runTest {
            // The headline of #27: item 2 throws (a bad feed), but items 3 and 4
            // must still be processed — the failure is isolated, not fatal.
            val visited = mutableListOf<Int>()

            val outcome =
                forEachIsolatingFailures(listOf(1, 2, 3, 4)) { item ->
                    visited += item
                    if (item == 2) error("feed $item blew up")
                }

            assertEquals(listOf(1, 2, 3, 4), visited, "items after the failure must still be processed")
            assertEquals(IsolatedSweepOutcome(succeeded = 3, failed = 1), outcome)
        }

    @Test
    fun countsEveryFailureWhenMultipleItemsThrow() =
        runTest {
            val outcome =
                forEachIsolatingFailures(listOf(1, 2, 3, 4)) { item ->
                    if (item % 2 == 0) error("feed $item blew up")
                }

            assertEquals(IsolatedSweepOutcome(succeeded = 2, failed = 2), outcome)
        }

    @Test
    fun rethrowsCancellationInsteadOfSwallowingIt() =
        runTest {
            // Coroutine cancellation (WorkManager stopping the worker) must
            // propagate — it must NOT be caught and mis-counted as a feed failure,
            // otherwise a cancelled worker would silently "complete" the sweep.
            val visited = mutableListOf<Int>()

            assertFailsWith<CancellationException> {
                forEachIsolatingFailures(listOf(1, 2, 3)) { item ->
                    visited += item
                    if (item == 2) throw CancellationException("worker stopped")
                }
            }

            assertEquals(listOf(1, 2), visited, "the sweep stops at the cancellation, not after it")
        }

    @Test
    fun emptyListYieldsZeroCounts() =
        runTest {
            val outcome = forEachIsolatingFailures(emptyList<Int>()) { error("must not run") }

            assertEquals(IsolatedSweepOutcome(succeeded = 0, failed = 0), outcome)
        }
}
