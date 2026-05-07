// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

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
 * Narrow seam over the `ExportLog` table. Kept as an interface so
 * [app.kofipod.pkm.PkmExportCoordinator] can be unit-tested without a mocking
 * framework — tests substitute [ExportLogRepositoryImpl] with an in-memory fake.
 *
 * Each row is keyed by composite PK `(itemKind, itemId, destinationKind)`, so
 * re-exporting the same item to the same destination overwrites the prior row
 * rather than appending — the table is a ledger of *current* state, not an
 * append-only audit log.
 *
 * Status transitions are caller-driven:
 * - [recordSuccess] writes `status = 'success'` with an optional [externalId].
 * - [markQueued] writes `status = 'queued'` (worker will retry).
 * - [markFailed] writes `status = 'failed'` with a human-readable error.
 */
interface ExportLogRepository {
    suspend fun find(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
    ): ExportLogEntry?

    suspend fun selectQueuedOrFailed(): List<ExportLogEntry>

    suspend fun recordSuccess(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        externalId: String?,
        nowMs: Long,
    )

    suspend fun markQueued(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        nowMs: Long,
    )

    suspend fun markFailed(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        message: String,
        nowMs: Long,
    )

    suspend fun deleteByItem(
        itemKind: String,
        itemId: String,
    )
}

/**
 * SQLDelight-backed implementation of [ExportLogRepository].
 *
 * All suspend boundaries hop to [kotlinx.coroutines.Dispatchers.Default] (never
 * `Dispatchers.IO`, which is JVM-only and breaks iOS compile).
 */
class ExportLogRepositoryImpl(db: app.kofipod.db.KofipodDatabase) : ExportLogRepository {
    private val q = db.exportLogQueries

    override suspend fun find(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
    ): ExportLogEntry? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            q.selectByKey(itemKind, itemId, destinationKind.wire)
                .executeAsOneOrNull()
                ?.let(::toDomain)
        }

    override suspend fun selectQueuedOrFailed(): List<ExportLogEntry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            q.selectQueuedOrFailed().executeAsList().mapNotNull(::toDomain)
        }

    override suspend fun recordSuccess(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        externalId: String?,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, externalId, STATUS_SUCCESS, null, nowMs)

    override suspend fun markQueued(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, null, STATUS_QUEUED, null, nowMs)

    override suspend fun markFailed(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        message: String,
        nowMs: Long,
    ) = upsert(itemKind, itemId, destinationKind, null, STATUS_FAILED, message, nowMs)

    override suspend fun deleteByItem(
        itemKind: String,
        itemId: String,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            q.deleteByItem(itemKind, itemId)
        }
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
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
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
