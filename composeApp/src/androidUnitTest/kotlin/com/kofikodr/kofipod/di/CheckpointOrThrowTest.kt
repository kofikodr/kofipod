// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.di

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [checkpointOrThrow]'s busy-retry-then-throw contract. The PRAGMA
 * wal_checkpoint(TRUNCATE) row may report busy != 0 (lock contention) or
 * log > checkpointed (partial drain). The previous implementation
 * discarded the row entirely and assumed success — silently emitting a
 * backup that omitted recent committed transactions.
 *
 * These tests use a hand-rolled SqlDriver stub that returns scripted
 * CheckpointResult rows on each PRAGMA call. The retry/throw logic is
 * tested independently of any real SQLite engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointOrThrowTest {
    @Test
    fun returnsWithoutThrowing_whenFirstAttemptDrainsCleanly() =
        runTest {
            val driver =
                scriptedDriver(
                    listOf(CheckpointResult(busy = 0, log = 0, checkpointed = 0)),
                )
            // Should not throw; consumes exactly one PRAGMA.
            checkpointOrThrow(driver)
            assertEquals(1, driver.pragmaCalls, "Single successful checkpoint must not retry")
        }

    @Test
    fun returnsWithoutThrowing_whenAllFramesCheckpointedEvenIfLogMatches() =
        runTest {
            // log == checkpointed → all WAL frames were replayed; busy=0 means
            // no lock contention. This must be treated as a success.
            val driver =
                scriptedDriver(
                    listOf(CheckpointResult(busy = 0, log = 5, checkpointed = 5)),
                )
            checkpointOrThrow(driver)
            assertEquals(1, driver.pragmaCalls)
        }

    @Test
    fun retriesOnBusy_thenSucceedsWithinAttemptBudget() =
        runTest {
            val driver =
                scriptedDriver(
                    listOf(
                        CheckpointResult(busy = 1, log = 0, checkpointed = 0),
                        CheckpointResult(busy = 1, log = 0, checkpointed = 0),
                        CheckpointResult(busy = 0, log = 3, checkpointed = 3),
                    ),
                )
            checkpointOrThrow(driver)
            assertEquals(3, driver.pragmaCalls, "Must retry through busy responses until success")
        }

    @Test
    fun retriesOnPartialDrain_thenSucceeds() =
        runTest {
            // log > checkpointed means some frames stayed in the WAL. Either
            // they should be drained on retry, or we fail the backup.
            val driver =
                scriptedDriver(
                    listOf(
                        CheckpointResult(busy = 0, log = 10, checkpointed = 5),
                        CheckpointResult(busy = 0, log = 10, checkpointed = 10),
                    ),
                )
            checkpointOrThrow(driver)
            assertEquals(2, driver.pragmaCalls)
        }

    @Test
    fun throws_afterAllAttemptsBusy() =
        runTest {
            // 5 attempts all busy → fail-closed. Backup must NOT proceed; emitting
            // an incomplete .kpbak is worse than a user-visible error.
            val driver =
                scriptedDriver(
                    List(5) { CheckpointResult(busy = 1, log = 0, checkpointed = 0) },
                )
            val thrown = assertFailsWith<IllegalStateException> { checkpointOrThrow(driver) }
            assertTrue(
                thrown.message!!.contains("WAL checkpoint did not drain"),
                "Error message must name the failure mode so the user-visible toast is meaningful",
            )
            assertEquals(5, driver.pragmaCalls, "Must exhaust the full retry budget before throwing")
        }

    @Test
    fun throws_afterAllAttemptsPartialDrain() =
        runTest {
            val driver =
                scriptedDriver(
                    List(5) { CheckpointResult(busy = 0, log = 10, checkpointed = 1) },
                )
            assertFailsWith<IllegalStateException> { checkpointOrThrow(driver) }
            assertEquals(5, driver.pragmaCalls)
        }

    @Test
    fun fail_closedWhenCursorReturnsNoRow() =
        runTest {
            // PRAGMA wal_checkpoint always emits a row in practice, but if it
            // ever returned an empty result set we'd be making a backup with
            // zero signal about the WAL state. Treat as busy. Pin
            // pragmaCalls == 5 so the retry loop is actually exercised — a
            // regression that throws immediately on attempt 1 would still
            // satisfy assertFailsWith, but the pragmaCalls assertion catches it.
            val driver = scriptedDriverWithEmptyRow()
            assertFailsWith<IllegalStateException> { checkpointOrThrow(driver) }
            assertEquals(5, driver.pragmaCalls, "Empty-row path must still exhaust the retry budget")
        }

    // --- Test SqlDriver wiring ----------------------------------------------

    private fun scriptedDriver(results: List<CheckpointResult>): TestDriver = TestDriver(results.toMutableList())

    private fun scriptedDriverWithEmptyRow(): TestDriver = TestDriver(mutableListOf(), emptyRow = true)

    private class TestDriver(
        private val scripted: MutableList<CheckpointResult>,
        private val emptyRow: Boolean = false,
    ) : SqlDriver {
        var pragmaCalls = 0
            private set

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            require(sql == "PRAGMA wal_checkpoint(TRUNCATE)") {
                "Test fake only handles the wal_checkpoint PRAGMA, got: $sql"
            }
            pragmaCalls++
            val row = if (emptyRow) null else scripted.removeFirstOrNull()
            return mapper(ScriptedCursor(row))
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.Value(0L)

        override fun close() {}

        override fun newTransaction(): QueryResult<Transacter.Transaction> = error("not used in test")

        override fun currentTransaction(): Transacter.Transaction? = null

        override fun addListener(
            vararg queryKeys: String,
            listener: app.cash.sqldelight.Query.Listener,
        ) {}

        override fun removeListener(
            vararg queryKeys: String,
            listener: app.cash.sqldelight.Query.Listener,
        ) {}

        override fun notifyListeners(vararg queryKeys: String) {}
    }

    private class ScriptedCursor(private val row: CheckpointResult?) : SqlCursor {
        private var advanced = false

        override fun next(): QueryResult<Boolean> {
            if (!advanced) {
                advanced = true
                return QueryResult.Value(row != null)
            }
            return QueryResult.Value(false)
        }

        override fun getString(index: Int): String? = error("not used")

        override fun getLong(index: Int): Long? {
            val r = row ?: return null
            return when (index) {
                0 -> r.busy.toLong()
                1 -> r.log.toLong()
                2 -> r.checkpointed.toLong()
                else -> null
            }
        }

        override fun getBytes(index: Int): ByteArray? = error("not used")

        override fun getDouble(index: Int): Double? = error("not used")

        override fun getBoolean(index: Int): Boolean? = error("not used")
    }
}
