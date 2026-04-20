// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.testing

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
