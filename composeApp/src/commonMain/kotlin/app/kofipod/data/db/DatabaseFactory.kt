// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import app.cash.sqldelight.db.SqlDriver
import app.kofipod.db.KofipodDatabase

expect class DatabaseFactory {
    fun createDriver(): SqlDriver
}

/**
 * Kept for compatibility with callers that want a one-shot DB without holding the
 * driver. The Koin graph in [app.kofipod.di.commonDataModule] now exposes the driver
 * separately so [app.kofipod.backup.DbFileBytes] can issue a WAL checkpoint before
 * reading the on-disk file (otherwise the snapshot would be missing recent writes
 * still in the `-wal` sidecar).
 */
fun buildDatabase(factory: DatabaseFactory): KofipodDatabase = KofipodDatabase(factory.createDriver())
