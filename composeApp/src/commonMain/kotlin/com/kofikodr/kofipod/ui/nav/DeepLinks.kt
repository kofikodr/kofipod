// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.nav

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Deep-link / notification / cold-start nav events.
 *
 * Previously backed by `MutableSharedFlow(replay = 0)`, which silently
 * dropped any emission made before a collector existed. MainActivity's
 * `handleIntent()` runs BEFORE `setContent { … AppShell() }` installs the
 * Compose collectors, so cold-start notification taps and intent-launched
 * deep links were lost.
 *
 * Now backed by a per-event `Channel(capacity = 1, DROP_OLDEST)`:
 *   - An emission before any collect runs is held in the channel's buffer
 *     until AppShell starts collecting, at which point the buffered value
 *     is delivered to the new collector.
 *   - Once delivered, the channel is empty again — repeated re-collection
 *     (e.g. a configuration-change recreation of AppShell) won't replay
 *     stale navigation.
 *   - If two emissions race a collector (very unlikely in this app), the
 *     newer one wins via DROP_OLDEST. For deep links that's the right call:
 *     the most recent user intent is what they expect.
 *
 * Multi-collector safety: `receiveAsFlow()` yields each received value to
 * exactly one collector. AppShell is the sole collector at runtime; if
 * recomposition cancels the LaunchedEffect and starts a new collector,
 * the new collector receives only values that arrive AFTER it starts — the
 * cancelled collector has already consumed any in-flight emission.
 */
object DeepLinks {
    private val openPlayerChannel = newEventChannel<Unit>()
    val openPlayer: Flow<Unit> = openPlayerChannel.receiveAsFlow()

    private val openEpisodeChannel = newEventChannel<String>()
    val openEpisode: Flow<String> = openEpisodeChannel.receiveAsFlow()

    private val openLibraryChannel = newEventChannel<Unit>()
    val openLibrary: Flow<Unit> = openLibraryChannel.receiveAsFlow()

    private val openSettingsChannel = newEventChannel<Unit>()
    val openSettings: Flow<Unit> = openSettingsChannel.receiveAsFlow()

    fun requestOpenPlayer() {
        openPlayerChannel.trySend(Unit)
    }

    fun requestOpenEpisode(episodeId: String) {
        openEpisodeChannel.trySend(episodeId)
    }

    fun requestOpenLibrary() {
        openLibraryChannel.trySend(Unit)
    }

    fun requestOpenSettings() {
        openSettingsChannel.trySend(Unit)
    }

    private fun <T> newEventChannel(): Channel<T> = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
