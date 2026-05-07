// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

/**
 * Schedules an out-of-process retry of any queued [app.kofipod.pkm.connections.ExportLogEntry]
 * rows in the database. Triggered by [app.kofipod.pkm.PkmExportCoordinator] on every
 * transient failure so a process death or transient network error can be recovered
 * without user intervention.
 *
 * Production implementations:
 *  - Android: [PkmExportSchedulerImpl] enqueues a unique OneTimeWorkRequest (Task 12).
 *  - iOS: [PkmExportSchedulerImpl] is a no-op (iOS is secondary target; recovery is user-driven).
 *
 * Kept as a plain interface (not a fun interface) so Task 12 can supply a
 * Context-bearing Android actual via the established "interface + expect class Impl"
 * pattern — see [ObsidianFolderWriter] and [OAuthTokenVault] for prior art.
 */
interface PkmExportScheduler {
    fun enqueue()
}
