// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.kofikodr.kofipod.db.KofipodDatabase

actual class DatabaseFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(KofipodDatabase.Schema, "kofipod.db")
}
