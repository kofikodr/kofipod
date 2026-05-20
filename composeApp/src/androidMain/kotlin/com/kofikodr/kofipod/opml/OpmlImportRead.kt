// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Max bytes we'll buffer for an OPML import. Real OPML subscription lists are
 * small — a heavy user with hundreds of feeds is still in the tens of KB; 4 MB
 * leaves an order-of-magnitude headroom while preventing a hostile or
 * misconfigured content provider from pinning the heap. Tests can inject a
 * smaller cap via the `maxBytes` parameter on the read helpers.
 */
const val DEFAULT_MAX_OPML_BYTES: Long = 4L * 1024L * 1024L

/**
 * Result of an OPML import-read attempt.
 *
 * Sealed because the two failure modes — the picker returned null/unreadable, vs.
 * the file is bigger than [DEFAULT_MAX_OPML_BYTES] — map to distinct user messages
 * downstream. A plain `ByteArray?` would conflate them.
 */
sealed interface OpmlReadResult {
    data class Ok(val bytes: ByteArray) : OpmlReadResult {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Ok && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Couldn't open the stream, or the stream errored mid-read. */
    data object Unreadable : OpmlReadResult

    /** Stream exceeded the cap. The original bytes are discarded. */
    data class TooLarge(val cap: Long) : OpmlReadResult
}

/**
 * Stream-read [input] into memory, refusing to return more than [maxBytes]. The
 * caller is responsible for closing [input]; we do not assume ownership so this
 * helper composes with `use { ... }` blocks at the call site.
 *
 * Pinned by `OpmlImportReadTest`.
 */
internal fun readCappedStream(
    input: InputStream,
    maxBytes: Long = DEFAULT_MAX_OPML_BYTES,
): OpmlReadResult {
    require(maxBytes in 1L..Int.MAX_VALUE.toLong()) {
        "maxBytes must be positive and fit in Int (got $maxBytes)"
    }
    return runCatching {
        val sink = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) return OpmlReadResult.TooLarge(cap = maxBytes)
            sink.write(buf, 0, read)
        }
        OpmlReadResult.Ok(sink.toByteArray())
    }.getOrElse { OpmlReadResult.Unreadable }
}

/**
 * Resolve [uri] via [contentResolver] with two gates: an early reject when
 * `OpenableColumns.SIZE` reports a declared size above [maxBytes], and a
 * streaming counter when the actual content exceeds the cap (some providers
 * lie or omit the size column).
 */
internal fun readCappedFromUri(
    contentResolver: ContentResolver,
    uri: Uri,
    maxBytes: Long = DEFAULT_MAX_OPML_BYTES,
): OpmlReadResult {
    val declared = queryDeclaredSize(contentResolver, uri)
    if (declared != null && declared > maxBytes) {
        return OpmlReadResult.TooLarge(cap = maxBytes)
    }
    val stream =
        runCatching { contentResolver.openInputStream(uri) }.getOrNull()
            ?: return OpmlReadResult.Unreadable
    return stream.use { readCappedStream(it, maxBytes) }
}

/**
 * `OpenableColumns.SIZE` is null/-1 for many providers (e.g. virtual files,
 * cloud-streamed providers); returning null here means "no usable hint, fall
 * back to the streaming gate" rather than treating the missing value as 0.
 */
private fun queryDeclaredSize(
    contentResolver: ContentResolver,
    uri: Uri,
): Long? {
    val cursor =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val idx = it.getColumnIndex(OpenableColumns.SIZE)
        if (idx < 0 || it.isNull(idx)) return null
        val value = runCatching { it.getLong(idx) }.getOrNull() ?: return null
        return if (value > 0L) value else null
    }
}

private const val BUFFER_SIZE = 8 * 1024
