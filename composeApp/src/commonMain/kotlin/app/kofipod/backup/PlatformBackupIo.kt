// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

/**
 * Reads the live SQLDelight DB file from disk as a single byte array. Backed on Android
 * by `Context.getDatabasePath("kofipod.db").readBytes()`, on iOS by an `error()`.
 *
 * Defined as a `fun interface` rather than an `expect fun` so the production binding can
 * inject context once at Koin resolution and unit tests can substitute a fake without
 * shimming a top-level expect.
 */
fun interface DbFileBytes {
    fun read(): ByteArray
}

/**
 * Writes a staged DB payload to a fixed path inside the app's `filesDir` so
 * [PendingRestore] can copy it over the live DB on the next cold start. Lives outside
 * the SQLDelight database directory on purpose: we never want SQLite to open the staged
 * file by accident.
 */
fun interface StageDbFile {
    fun write(bytes: ByteArray)
}
