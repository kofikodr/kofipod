// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Single source of truth for PKM destination connection rows ([PkmConnection]).
 * Owns the choreography between the SQLDelight `PkmConnection` table and the
 * encrypted [OAuthTokenVault]: token material is written to the vault before
 * the row is upserted, and cleared after the row is deleted, so a row in the
 * table is the canonical signal that a connection is "live" and the token
 * (if any) is retrievable.
 *
 * One row per [ConnectionKind] — `id` is set to the kind's wire string so the
 * upsert is idempotent across a re-connect.
 */
class PkmConnectionRepository(
    db: KofipodDatabase,
    private val vault: OAuthTokenVault,
) {
    private val queries = db.pkmConnectionQueries

    fun observeAll(): Flow<List<PkmConnection>> =
        queries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.mapNotNull(::toDomain)
        }

    fun observe(kind: ConnectionKind): Flow<PkmConnection?> =
        queries.selectByKind(kind.wire).asFlow().mapToOneOrNull(Dispatchers.Default).map { row ->
            row?.let(::toDomain)
        }

    /**
     * Writes the token (if provided) to the vault, then upserts the connection
     * row. Connecting an already-connected kind replaces the row and overwrites
     * the existing vault entry — callers do not need to call [disconnect] first.
     *
     * If the prior row stored its secret under a different [tokenRef] (a key
     * rotation), the prior vault entry is cleared before the new one is
     * written so stale secrets do not linger in `kofipod_secure`.
     *
     * @param tokenRef opaque vault key (e.g. `"readwise.token"`); pass `null`
     *   for filesystem-style connections that don't need a secret.
     * @param tokenValue the secret to store at [tokenRef]; ignored when
     *   [tokenRef] is `null`.
     */
    suspend fun connect(
        kind: ConnectionKind,
        tokenRef: String?,
        tokenValue: String?,
        folderUri: String?,
        nowMs: Long,
    ) {
        val existing =
            withContext(Dispatchers.Default) {
                queries.selectByKind(kind.wire).executeAsOneOrNull()
            }
        val priorRef = existing?.tokenRef
        if (priorRef != null && priorRef != tokenRef) {
            vault.clear(priorRef)
        }
        if (tokenRef != null && tokenValue != null) {
            vault.put(tokenRef, tokenValue)
        }
        withContext(Dispatchers.Default) {
            queries.upsert(
                id = kind.wire,
                kind = kind.wire,
                tokenRef = tokenRef,
                folderUri = folderUri,
                enabledAt = nowMs,
                lastSyncAt = null,
            )
        }
    }

    /**
     * Removes the connection row, then clears the associated vault entry.
     * The order matters: deleting the DB row first guarantees that an
     * interrupted disconnect (e.g. process death between the two writes)
     * leaves an orphan vault entry rather than the inverse — a row that
     * appears connected but whose token is no longer retrievable would
     * silently break every subsequent export with no UI signal.
     */
    suspend fun disconnect(kind: ConnectionKind) {
        val current =
            withContext(Dispatchers.Default) {
                queries.selectByKind(kind.wire).executeAsOneOrNull()
            }
        withContext(Dispatchers.Default) { queries.deleteByKind(kind.wire) }
        current?.tokenRef?.let { vault.clear(it) }
    }

    suspend fun markSynced(
        kind: ConnectionKind,
        nowMs: Long,
    ) {
        withContext(Dispatchers.Default) { queries.updateLastSync(nowMs, kind.wire) }
    }

    private fun toDomain(row: app.kofipod.db.PkmConnection): PkmConnection? {
        val resolvedKind = ConnectionKind.fromWire(row.kind) ?: return null
        return PkmConnection(
            id = row.id,
            kind = resolvedKind,
            tokenRef = row.tokenRef,
            folderUri = row.folderUri,
            enabledAtMs = row.enabledAt,
            lastSyncAtMs = row.lastSyncAt,
        )
    }
}
