// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

import app.kofipod.testing.inMemoryDatabase
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
        repo = ExportLogRepository(inMemoryDatabase())
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
            repo.markFailed("snippet", "s1", ConnectionKind.Readwise, "transient 502", 100L)
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
    fun selectQueuedOrFailed_returns_only_non_success_rows() =
        runTest {
            repo.recordSuccess("snippet", "ok", ConnectionKind.Readwise, "ext-x", 10L)
            repo.markFailed("snippet", "bad", ConnectionKind.Readwise, "boom", 20L)
            repo.markQueued("snippet", "wait", ConnectionKind.Readwise, 30L)

            val rows = repo.selectQueuedOrFailed()

            assertEquals(2, rows.size)
            assertNull(rows.firstOrNull { it.itemId == "ok" })
        }
}
