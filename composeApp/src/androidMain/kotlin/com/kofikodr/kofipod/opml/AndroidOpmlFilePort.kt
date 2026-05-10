// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

/**
 * Bridges suspending [OpmlFilePort] calls (made from common code) to the Activity-scoped
 * SAF launchers (which are Compose-rooted). The flows below are consumed by the
 * `OpmlPickerHost` composable; each emission carries a [CompletableDeferred] the host
 * resolves once the launcher returns.
 *
 * Buffered with `extraBufferCapacity = 1` so a request queued before the host has
 * subscribed isn't lost, but back-to-back requests still suspend the second caller until
 * the first completes (which matches [OpmlController]'s single-flight guarantee).
 */
class AndroidOpmlFilePort : OpmlFilePort {
    data class ExportRequest(
        val suggestedFilename: String,
        val content: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _imports =
        MutableSharedFlow<CompletableDeferred<ByteArray?>>(replay = 0, extraBufferCapacity = 1)
    val imports: SharedFlow<CompletableDeferred<ByteArray?>> = _imports.asSharedFlow()

    private val _exports =
        MutableSharedFlow<ExportRequest>(replay = 0, extraBufferCapacity = 1)
    val exports: SharedFlow<ExportRequest> = _exports.asSharedFlow()

    // The picker host's LaunchedEffect can be cancelled across an Activity recreation
    // (config change, low-memory recreate). If that happens between request emit and
    // result, the deferred would otherwise sit unresolved forever and the controller's
    // single-flight guard would never clear. The timeout caps that worst case at 5
    // minutes — long enough that a real picker session (file-manager browsing, drive
    // download) won't trip it, short enough that the user gets unstuck if Compose drops
    // the host.
    override suspend fun pickImport(): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        _imports.emit(deferred)
        return withTimeout(PICKER_TIMEOUT_MS) { deferred.await() }
    }

    override suspend fun saveExport(
        suggestedFilename: String,
        content: String,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _exports.emit(ExportRequest(suggestedFilename, content, deferred))
        return withTimeout(PICKER_TIMEOUT_MS) { deferred.await() }
    }

    private companion object {
        const val PICKER_TIMEOUT_MS = 5L * 60_000L
    }
}
