// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

/**
 * Phase of a SAF-driven backup or restore — surfaced to the UI as inline progress on the
 * Settings rows. Distinct from any per-screen state. Single-flight: at most one operation
 * runs at a time (a second tap while [BackingUp] / [Restoring] is a no-op).
 *
 * Mirrors [app.kofipod.opml.OpmlAction] — keep the shapes aligned so future shared
 * "operation phase" UI patterns can lift them into a common primitive.
 */
sealed interface BackupAction {
    data object Idle : BackupAction

    data object BackingUp : BackupAction

    data object Restoring : BackupAction

    data class Error(val message: String) : BackupAction
}
