// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.SharedFlow

/**
 * Boundary interface for the platform [DownloadEngine]. Keeping repositories and tests on this
 * interface means JVM unit tests can substitute a trivial fake without constructing the Android
 * actual (which needs a `Context` and a file system).
 */
interface DownloadEngineApi {
    val events: SharedFlow<DownloadProgress>

    fun enqueue(job: DownloadJob)

    fun cancel(episodeId: String)

    fun delete(episodeId: String)
}
