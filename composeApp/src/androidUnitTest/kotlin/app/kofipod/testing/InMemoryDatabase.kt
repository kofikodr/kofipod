// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.db.KofipodDatabase

/**
 * Builds a fresh [KofipodDatabase] backed by an in-memory SQLite (JDBC) driver and runs the
 * full SQLDelight schema. Each call returns a clean, isolated DB instance — callers do not
 * need to close it, as the in-memory store is released when the driver is garbage collected.
 */
fun inMemoryDatabase(): KofipodDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    KofipodDatabase.Schema.create(driver)
    return KofipodDatabase(driver)
}

/**
 * Same shape as [inMemoryDatabase] but also returns the underlying [SqlDriver]
 * for tests that need raw `executeQuery`/`execute` access — used by repositories
 * that bypass SQLDelight's typed-query layer (e.g. FTS5 search where the
 * default dialect doesn't parse `ORDER BY rank` + `snippet(...)`).
 *
 * `PRAGMA foreign_keys = ON` is set explicitly after schema creation because
 * the JDBC driver does not enable FK enforcement by default (unlike the Android
 * driver). This is required for ON DELETE CASCADE triggers to fire correctly.
 *
 * The caller is responsible for closing the returned [SqlDriver] after use.
 */
fun inMemoryDatabaseWithDriver(): Pair<KofipodDatabase, SqlDriver> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    KofipodDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return KofipodDatabase(driver) to driver
}
