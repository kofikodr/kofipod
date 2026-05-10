// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.util

/**
 * Single-slot holder used to bridge a launcher-callback closure (which captures values
 * at composition time) to the latest pending request. Compose remembers the holder
 * itself; the slot is mutated when a new request is launched and cleared on result.
 *
 * Used by [com.kofikodr.kofipod.opml.OpmlPickerHost] and [com.kofikodr.kofipod.backup.BackupPickerHost]
 * — both bridge a `SharedFlow<CompletableDeferred<…>>` to an `ActivityResultLauncher`
 * via the same dance.
 */
internal class PendingHolder<T : Any> {
    private var slot: T? = null

    fun set(value: T) {
        slot = value
    }

    fun take(): T? {
        val v = slot
        slot = null
        return v
    }
}
