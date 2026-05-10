// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing decision for [SchemeAwareDataSource]. Only `file://` (and schemeless) URIs
 * should bypass the streaming cache. The full delegation path (open/read/close) is
 * covered by emulator verification — testing it here would require constructing a
 * Media3 [androidx.media3.datasource.DataSpec], which transitively needs `Uri.parse`,
 * and the project deliberately avoids Robolectric (see AiConfigRepositoryTest).
 */
class SchemeAwareDataSourceTest {
    @Test
    fun `file scheme routes locally`() {
        assertTrue(SchemeAwareDataSource.isLocalScheme("file"))
    }

    @Test
    fun `null scheme routes locally`() {
        // Schemeless URIs are treated as local for parity with java.net.URI defaults.
        assertTrue(SchemeAwareDataSource.isLocalScheme(null))
    }

    @Test
    fun `http scheme routes through cache`() {
        assertFalse(SchemeAwareDataSource.isLocalScheme("http"))
    }

    @Test
    fun `https scheme routes through cache`() {
        assertFalse(SchemeAwareDataSource.isLocalScheme("https"))
    }

    @Test
    fun `content scheme routes through cache`() {
        // content:// is rare but present (e.g. Media3 demo flows). It's not a "the file is
        // already on our local-downloads path" signal, so we send it through the cache
        // wrapper rather than the local factory.
        assertFalse(SchemeAwareDataSource.isLocalScheme("content"))
    }

    @Test
    fun `asset scheme routes through cache`() {
        assertFalse(SchemeAwareDataSource.isLocalScheme("asset"))
    }

    @Test
    fun `uppercase file scheme does not route locally`() {
        // Scheme matching is case-sensitive here on purpose — every URI we emit from
        // DownloadRepository.localUriFor uses lowercase "file://", so an unexpected
        // casing means a foreign URI we shouldn't fast-path.
        assertFalse(SchemeAwareDataSource.isLocalScheme("FILE"))
    }
}
