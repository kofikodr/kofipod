// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.nav

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DeepLinks {
    private val _openPlayer =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val openPlayer: SharedFlow<Unit> = _openPlayer.asSharedFlow()

    private val _openEpisode =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val openEpisode: SharedFlow<String> = _openEpisode.asSharedFlow()

    private val _openLibrary =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val openLibrary: SharedFlow<Unit> = _openLibrary.asSharedFlow()

    private val _openSettings =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val openSettings: SharedFlow<Unit> = _openSettings.asSharedFlow()

    fun requestOpenPlayer() {
        _openPlayer.tryEmit(Unit)
    }

    fun requestOpenEpisode(episodeId: String) {
        _openEpisode.tryEmit(episodeId)
    }

    fun requestOpenLibrary() {
        _openLibrary.tryEmit(Unit)
    }

    fun requestOpenSettings() {
        _openSettings.tryEmit(Unit)
    }
}
