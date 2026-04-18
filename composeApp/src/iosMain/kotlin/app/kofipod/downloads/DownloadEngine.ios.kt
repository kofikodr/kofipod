// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

actual class DownloadEngine {
    private val _events = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    actual val events: SharedFlow<DownloadProgress> = _events.asSharedFlow()
    actual fun enqueue(job: DownloadJob) { /* TODO URLSession */ }
    actual fun cancel(episodeId: String) {}
    actual fun delete(episodeId: String) {}
}
