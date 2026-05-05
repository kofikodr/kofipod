// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

actual fun sha256(bytes: ByteArray): String {
    error("sha256 not supported on iOS — SAF backup feature isn't surfaced on this target.")
}
