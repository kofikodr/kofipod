// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

/**
 * Schedules an out-of-process retry of any queued
 * [com.kofikodr.kofipod.pkm.connections.ExportLogEntry] rows in the database. Triggered
 * by [com.kofikodr.kofipod.pkm.PkmExportCoordinator] on every transient failure so a
 * process death or transient network error can be recovered without user
 * intervention.
 *
 * Production implementations:
 *  - Android: `AndroidPkmExportScheduler` enqueues a unique
 *    [androidx.work.OneTimeWorkRequest] backed by `PkmExportWorker`, with
 *    `NetworkType.CONNECTED` and `BackoffPolicy.EXPONENTIAL`.
 *  - iOS: `IosPkmExportScheduler` is a no-op. Slice 6 ships Android-first;
 *    iOS retry is intentionally user-driven (re-tap Export).
 *
 * Modeled as a plain interface (not a `fun interface`) so unit tests can
 * supply a recording fake without subclassing the platform actual — the
 * Android actual carries a [android.content.Context] in its primary
 * constructor, which would force tests through `expect class Impl` ceremony
 * for no benefit.
 */
interface PkmExportScheduler {
    fun enqueue()
}
