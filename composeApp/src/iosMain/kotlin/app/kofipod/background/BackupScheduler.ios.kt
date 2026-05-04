// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

actual class BackupScheduler {
    actual fun enable() {
        // No-op: SAF backup feature isn't surfaced on iOS in v1.
    }

    actual fun disable() {
        // No-op
    }
}
