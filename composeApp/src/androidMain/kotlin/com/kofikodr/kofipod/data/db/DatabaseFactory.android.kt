// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.kofikodr.kofipod.db.KofipodDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

actual class DatabaseFactory(private val context: Context) {
    /**
     * Use Requery's bundled SQLite (with FTS5 compiled in) instead of the
     * Android system SQLite, which omits FTS5 — Slice 2's LibrarySearchIndex
     * CREATE VIRTUAL TABLE relies on FTS5's `MATCH` + `snippet()` + `rank`
     * and crashed on system SQLite with "no such module: fts5". iOS bundled
     * SQLite already has FTS5, and the JDBC sqlite-driver used in unit tests
     * does too — this brings Android in line.
     */
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = KofipodDatabase.Schema,
            context = context,
            name = "kofipod.db",
            factory = RequerySQLiteOpenHelperFactory(),
        )
}
