// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

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

    fun requestOpenPlayer() {
        _openPlayer.tryEmit(Unit)
    }
}
