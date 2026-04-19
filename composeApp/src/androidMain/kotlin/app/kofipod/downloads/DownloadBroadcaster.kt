// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DownloadBroadcaster {
    private val _events = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadProgress> = _events.asSharedFlow()

    suspend fun emit(p: DownloadProgress) {
        _events.emit(p)
    }

    fun tryEmit(p: DownloadProgress) {
        _events.tryEmit(p)
    }
}
