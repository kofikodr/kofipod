// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

actual class DownloadEngine : DownloadEngineApi {
    private val _events = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    actual override val events: SharedFlow<DownloadProgress> = _events.asSharedFlow()

    actual override fun enqueue(job: DownloadJob) { /* TODO URLSession */ }

    actual override fun cancel(episodeId: String) {}

    actual override fun delete(episodeId: String) {}
}
