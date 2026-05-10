// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

actual class ZipBuilder {
    actual fun addEntry(
        name: String,
        bytes: ByteArray,
    ) {
        error("zip not supported on iOS — SAF backup feature isn't surfaced on this target.")
    }

    actual fun finish(): ByteArray {
        error("zip not supported on iOS — SAF backup feature isn't surfaced on this target.")
    }
}

actual fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    error("zip not supported on iOS — SAF backup feature isn't surfaced on this target.")
}
