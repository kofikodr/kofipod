// SPDX-License-Identifier: GPL-3.0-or-later
// Media3's DataSource, DataSpec, and TransferListener are all marked @UnstableApi —
// there is no stable equivalent for the routing wrapper this file implements. The opt-in
// is intentional and scoped to this file only.
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kofikodr.kofipod.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A [DataSource] that picks one of two delegate factories based on the [DataSpec]'s URI
 * scheme. `file://` (and schemeless) URIs go to [localFactory] without any cache wrapper;
 * everything else flows through [networkFactory], which is where the streaming
 * [androidx.media3.datasource.cache.CacheDataSource] lives. This keeps downloaded episodes
 * — already on disk under the app's files/downloads/ — out of the streaming SimpleCache,
 * avoiding redundant copies and LRU eviction of genuinely-streamed spans.
 *
 * `addTransferListener` is recorded before [open] and replayed onto the inner data source
 * once a delegate is chosen. ExoPlayer registers listeners up-front via the factory, so
 * this preserves the same observability (BandwidthMeter, etc.) as the unwrapped path.
 */
internal class SchemeAwareDataSource(
    private val localFactory: DataSource.Factory,
    private val networkFactory: DataSource.Factory,
) : DataSource {
    // Listeners registered before [open] is called for the first time on this instance.
    // After [open], new registrations are forwarded directly to [inner] and this list stays
    // empty — keeping the invariant that listeners are never registered twice on the same
    // inner data source even if Media3 reuses this wrapper across open/close cycles.
    private val pendingListeners = mutableListOf<TransferListener>()
    private var inner: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        val current = inner
        if (current != null) {
            current.addTransferListener(transferListener)
        } else {
            pendingListeners += transferListener
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = if (isLocalScheme(dataSpec.uri.scheme)) localFactory else networkFactory
        val ds = factory.createDataSource()
        pendingListeners.forEach(ds::addTransferListener)
        // Listeners now live on `ds`; subsequent addTransferListener calls forward to it
        // directly. Clearing here also prevents replay onto a future inner if this instance
        // is reused after close().
        pendingListeners.clear()
        inner = ds
        return ds.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = requireNotNull(inner) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = inner?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            inner?.close()
        } finally {
            inner = null
        }
    }

    class Factory(
        private val localFactory: DataSource.Factory,
        private val networkFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeAwareDataSource(localFactory, networkFactory)
    }

    companion object {
        // file:// is the only scheme our DownloadRepository emits for completed downloads.
        // Schemeless URIs are treated as local for parity with java.net.URI defaults; in
        // practice ExoPlayer never produces them for our MediaItems.
        internal fun isLocalScheme(scheme: String?): Boolean = scheme == null || scheme == "file"
    }
}
