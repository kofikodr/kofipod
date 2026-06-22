// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.connections

import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExportLogRepositoryTest {
    private lateinit var repo: ExportLogRepository

    @BeforeTest
    fun setup() {
        repo = ExportLogRepositoryImpl(inMemoryDatabase())
    }

    @Test
    fun recordSuccess_upserts_row_with_externalId_and_success_status() =
        runTest {
            repo.recordSuccess(
                itemKind = "snippet",
                itemId = "s1",
                destinationKind = ConnectionKind.Readwise,
                externalId = "ext-1",
                nowMs = 100L,
            )

            val found = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertNotNull(found)
            assertEquals("ext-1", found.externalId)
            assertEquals("success", found.status)
            assertEquals(100L, found.exportedAtMs)
            assertEquals(ConnectionKind.Readwise, found.destinationKind)
        }

    @Test
    fun re_export_to_same_destination_overwrites_existing_row() =
        runTest {
            repo.recordSuccess("snippet", "s1", ConnectionKind.Readwise, "ext-1", 100L)
            repo.recordSuccess("snippet", "s1", ConnectionKind.Readwise, "ext-1", 200L)

            val found = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals(200L, found?.exportedAtMs)
        }

    @Test
    fun markFailed_records_failed_status_with_error_message() =
        runTest {
            repo.markFailed(
                itemKind = "bookmark",
                itemId = "b1",
                destinationKind = ConnectionKind.Obsidian,
                externalId = null,
                message = "permission revoked",
                nowMs = 50L,
            )

            val found = repo.find("bookmark", "b1", ConnectionKind.Obsidian)
            assertEquals("failed", found?.status)
            assertEquals("permission revoked", found?.errorMessage)
        }

    @Test
    fun recordSuccess_after_markFailed_clears_errorMessage_to_null() =
        runTest {
            repo.markFailed("snippet", "s1", ConnectionKind.Readwise, externalId = null, message = "transient 502", nowMs = 100L)
            val afterFailure = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("failed", afterFailure?.status)
            assertEquals("transient 502", afterFailure?.errorMessage)

            repo.recordSuccess("snippet", "s1", ConnectionKind.Readwise, externalId = "ext-7", nowMs = 200L)

            val afterRecovery = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("success", afterRecovery?.status)
            assertNull(afterRecovery?.errorMessage)
            assertEquals("ext-7", afterRecovery?.externalId)
        }

    @Test
    fun markQueued_with_externalId_preserves_it_so_retry_can_patch() =
        runTest {
            // Issue #51: a prior export stored a Readwise externalId. A transient
            // re-export failure re-queues the row; the externalId must survive so the
            // worker's next attempt PATCHes the existing highlight instead of POSTing a
            // duplicate. The caller (PkmExportCoordinator) passes the id it read before
            // the attempt.
            repo.recordSuccess("snippet", "s1", ConnectionKind.Readwise, externalId = "rw-123", nowMs = 100L)

            repo.markQueued("snippet", "s1", ConnectionKind.Readwise, externalId = "rw-123", nowMs = 200L)

            val queued = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("queued", queued?.status)
            assertEquals("rw-123", queued?.externalId, "the queued row must keep the externalId")
            assertEquals(200L, queued?.exportedAtMs)
        }

    @Test
    fun markFailed_with_externalId_preserves_it() =
        runTest {
            repo.recordSuccess("snippet", "s1", ConnectionKind.Readwise, externalId = "rw-456", nowMs = 100L)

            repo.markFailed("snippet", "s1", ConnectionKind.Readwise, externalId = "rw-456", message = "401", nowMs = 200L)

            val failed = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("failed", failed?.status)
            assertEquals("rw-456", failed?.externalId, "the failed row must keep the externalId")
            assertEquals("401", failed?.errorMessage)
        }

    @Test
    fun markQueued_with_null_externalId_stays_null() =
        runTest {
            // First-time export (no prior remote record) that fails transiently: the
            // coordinator passes externalId = null, and the queued row must record null
            // so the next retry POSTs (creates) rather than PATCHing a non-existent id.
            repo.markQueued("snippet", "s1", ConnectionKind.Readwise, externalId = null, nowMs = 100L)

            val queued = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("queued", queued?.status)
            assertNull(queued?.externalId, "a first-time export failure must record a null externalId")
        }

    @Test
    fun markFailed_with_null_externalId_stays_null() =
        runTest {
            repo.markFailed("snippet", "s1", ConnectionKind.Readwise, externalId = null, message = "401", nowMs = 100L)

            val failed = repo.find("snippet", "s1", ConnectionKind.Readwise)
            assertEquals("failed", failed?.status)
            assertNull(failed?.externalId, "a first-time export failure must record a null externalId")
            assertEquals("401", failed?.errorMessage)
        }

    @Test
    fun selectQueuedOrFailed_returns_only_non_success_rows() =
        runTest {
            repo.recordSuccess("snippet", "ok", ConnectionKind.Readwise, "ext-x", 10L)
            repo.markFailed("snippet", "bad", ConnectionKind.Readwise, externalId = null, message = "boom", nowMs = 20L)
            repo.markQueued("snippet", "wait", ConnectionKind.Readwise, externalId = null, nowMs = 30L)

            val rows = repo.selectQueuedOrFailed()

            assertEquals(2, rows.size)
            assertNull(rows.firstOrNull { it.itemId == "ok" })
        }
}
