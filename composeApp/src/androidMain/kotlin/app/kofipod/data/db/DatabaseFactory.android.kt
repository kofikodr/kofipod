// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.kofipod.db.KofipodDatabase

actual class DatabaseFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(KofipodDatabase.Schema, context, "kofipod.db")
}
