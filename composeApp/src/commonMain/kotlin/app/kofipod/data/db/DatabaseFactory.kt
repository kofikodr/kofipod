// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import app.cash.sqldelight.db.SqlDriver
import app.kofipod.db.KofipodDatabase

expect class DatabaseFactory {
    fun createDriver(): SqlDriver
}

fun buildDatabase(factory: DatabaseFactory): KofipodDatabase = KofipodDatabase(factory.createDriver())
