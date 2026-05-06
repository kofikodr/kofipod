// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain projection of an `ExportLog` row. Acts as both an idempotency ledger
 * (one row per `(itemKind, itemId, destinationKind)` triple) and a retry queue
 * (rows in `queued` or `failed` status are picked up by the export worker).
 */
data class ExportLogEntry(
    val itemKind: String,
    val itemId: String,
    val destinationKind: ConnectionKind,
    val externalId: String?,
    val exportedAtMs: Long,
    val status: String,
    val errorMessage: String?,
)

/**
 * Single source of truth for export-attempt history against the `ExportLog`
 * table. Each row is keyed by composite PK `(itemKind, itemId, destinationKind)`,
 * so re-exporting the same item to the same destination overwrites the prior
 * row rather than appending — the table is a ledger of *current* state, not an
 * append-only audit log.
 *
 * Status transitions are caller-driven:
 * - [recordSuccess] writes `status = 'success'` with an optional [externalId].
 * - [markQueued] writes `status = 'queued'` (worker will retry).
 * - [markFailed] writes `status = 'failed'` with a human-readable error.
 *
 * All suspend boundaries hop to [Dispatchers.Default] (never `Dispatchers.IO`,
 * which is JVM-only and breaks iOS compile).
 */
class ExportLogRepository(db: KofipodDatabase) {
    private val q = db.exportLogQueries

    suspend fun find(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
    ): ExportLogEntry? =
        withContext(Dispatchers.Default) {
            q.selectByKey(itemKind, itemId, destinationKind.wire)
                .executeAsOneOrNull()
                ?.let(::toDomain)
        }

    suspend fun selectQueuedOrFailed(): List<ExportLogEntry> =
        withContext(Dispatchers.Default) {
            q.selectQueuedOrFailed().executeAsList().mapNotNull(::toDomain)
        }

    suspend fun recordSuccess(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        externalId: String?,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, externalId, STATUS_SUCCESS, null, nowMs)

    suspend fun markQueued(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, null, STATUS_QUEUED, null, nowMs)

    suspend fun markFailed(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        message: String,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, null, STATUS_FAILED, message, nowMs)

    suspend fun deleteByItem(
        itemKind: String,
        itemId: String,
    ) {
        withContext(Dispatchers.Default) { q.deleteByItem(itemKind, itemId) }
    }

    private suspend fun upsert(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        externalId: String?,
        status: String,
        errorMessage: String?,
        nowMs: Long,
    ) {
        withContext(Dispatchers.Default) {
            q.upsert(
                itemKind = itemKind,
                itemId = itemId,
                destinationKind = destinationKind.wire,
                externalId = externalId,
                exportedAt = nowMs,
                status = status,
                errorMessage = errorMessage,
            )
        }
    }

    /**
     * Returns `null` for rows whose `destinationKind` is unknown to the current
     * build (e.g. a row written by a newer version after a downgrade). Callers
     * that surface ledger entries to the UI use `mapNotNull` so a stray row
     * never tears down the screen — mirrors `PkmConnectionRepository.toDomain`.
     */
    private fun toDomain(row: app.kofipod.db.ExportLog): ExportLogEntry? {
        val kind = ConnectionKind.fromWire(row.destinationKind) ?: return null
        return ExportLogEntry(
            itemKind = row.itemKind,
            itemId = row.itemId,
            destinationKind = kind,
            externalId = row.externalId,
            exportedAtMs = row.exportedAt,
            status = row.status,
            errorMessage = row.errorMessage,
        )
    }

    private companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_QUEUED = "queued"
        const val STATUS_FAILED = "failed"
    }
}
