// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkmConnectionRepositoryTest {
    private fun build(): Triple<PkmConnectionRepository, FakeOAuthTokenVault, app.kofipod.db.KofipodDatabase> {
        val db = inMemoryDatabase()
        val vault = FakeOAuthTokenVault()
        val repo = PkmConnectionRepository(db, vault)
        return Triple(repo, vault, db)
    }

    @Test
    fun connect_inserts_row_and_stores_token_in_vault() =
        runTest {
            val (repo, vault, _) = build()

            repo.connect(
                kind = ConnectionKind.Readwise,
                tokenRef = "readwise.token",
                tokenValue = "rw-secret",
                folderUri = null,
                nowMs = 1_000L,
            )

            val row = repo.observe(ConnectionKind.Readwise).first()
            assertNotNull(row)
            assertEquals(ConnectionKind.Readwise, row.kind)
            assertEquals("readwise.token", row.tokenRef)
            assertNull(row.folderUri)
            assertEquals(1_000L, row.enabledAtMs)
            assertNull(row.lastSyncAtMs)
            assertEquals("rw-secret", vault.store["readwise.token"])
        }

    @Test
    fun disconnect_removes_row_and_clears_vault_entry() =
        runTest {
            val (repo, vault, _) = build()
            repo.connect(ConnectionKind.Readwise, "readwise.token", "rw-secret", null, 1_000L)

            repo.disconnect(ConnectionKind.Readwise)

            assertNull(repo.observe(ConnectionKind.Readwise).first())
            assertNull(vault.store["readwise.token"])
        }

    @Test
    fun obsidian_connection_persists_folder_uri_without_token() =
        runTest {
            val (repo, vault, _) = build()

            repo.connect(
                kind = ConnectionKind.Obsidian,
                tokenRef = null,
                tokenValue = null,
                folderUri = "content://tree/abc",
                nowMs = 2_000L,
            )

            val row = repo.observe(ConnectionKind.Obsidian).first()
            assertNotNull(row)
            assertEquals(ConnectionKind.Obsidian, row.kind)
            assertEquals("content://tree/abc", row.folderUri)
            assertNull(row.tokenRef)
            assertTrue(vault.store.isEmpty())
        }

    @Test
    fun connect_replaces_existing_row_and_clears_old_token() =
        runTest {
            val (repo, vault, _) = build()
            repo.connect(ConnectionKind.Readwise, "readwise.token", "rw-old", null, 1_000L)

            repo.connect(ConnectionKind.Readwise, "readwise.token", "rw-new", null, 2_000L)

            val row = repo.observe(ConnectionKind.Readwise).first()
            assertNotNull(row)
            assertEquals(2_000L, row.enabledAtMs)
            assertEquals("rw-new", vault.store["readwise.token"])
        }

    @Test
    fun observeAll_emits_all_connection_rows() =
        runTest {
            val (repo, _, _) = build()
            repo.connect(ConnectionKind.Readwise, "readwise.token", "rw", null, 1_000L)
            repo.connect(ConnectionKind.Obsidian, null, null, "content://tree/x", 2_000L)

            val rows = repo.observeAll().first()

            assertEquals(2, rows.size)
            val kinds = rows.map { it.kind }.toSet()
            assertEquals(setOf(ConnectionKind.Readwise, ConnectionKind.Obsidian), kinds)
        }

    @Test
    fun markSynced_updates_lastSyncAt_for_kind() =
        runTest {
            val (repo, _, _) = build()
            repo.connect(ConnectionKind.Readwise, "readwise.token", "rw", null, 1_000L)

            repo.markSynced(ConnectionKind.Readwise, nowMs = 5_000L)

            val row = repo.observe(ConnectionKind.Readwise).first()
            assertNotNull(row)
            assertEquals(5_000L, row.lastSyncAtMs)
        }

    @Test
    fun observe_returns_null_when_kind_not_connected() =
        runTest {
            val (repo, _, _) = build()
            assertNull(repo.observe(ConnectionKind.Notion).first())
        }

    @Test
    fun markSynced_on_unconnected_kind_is_a_silent_noop() =
        runTest {
            val (repo, _, _) = build()

            repo.markSynced(ConnectionKind.Readwise, nowMs = 9_999L)

            assertNull(repo.observe(ConnectionKind.Readwise).first())
        }

    @Test
    fun reconnect_with_different_tokenRef_clears_old_vault_entry() =
        runTest {
            val (repo, vault, _) = build()
            repo.connect(ConnectionKind.Readwise, "readwise.token.v1", "old-secret", null, 1_000L)

            repo.connect(ConnectionKind.Readwise, "readwise.token.v2", "new-secret", null, 2_000L)

            assertNull(vault.store["readwise.token.v1"])
            assertEquals("new-secret", vault.store["readwise.token.v2"])
            val row = repo.observe(ConnectionKind.Readwise).first()
            assertNotNull(row)
            assertEquals("readwise.token.v2", row.tokenRef)
        }
}

private class FakeOAuthTokenVault : OAuthTokenVault {
    val store = mutableMapOf<String, String>()

    override suspend fun put(
        key: String,
        token: String,
    ) {
        store[key] = token
    }

    override suspend fun get(key: String): String? = store[key]

    override suspend fun clear(key: String) {
        store.remove(key)
    }
}
