// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins [sourceUriRoute]'s scheme-routing decision. Get this wrong and
 * Media3's DefaultMediaSourceFactory picks the wrong DataSource:
 *   - `http://…` interpreted as a path → Transformer tries to open as a
 *     local file → FileNotFoundException, "Export failed" toast.
 *   - `/data/local/file.mp3` interpreted as a URL → Transformer tries to
 *     resolve a network host → user waits through a timeout.
 *
 * Routing is a pure-Kotlin decision; the Uri construction itself lives at
 * the exporter boundary so the rule is testable without Robolectric.
 *
 * Note on project testing scope: the full SnippetExporter export pipeline
 * (Media3 Transformer driving native codecs) is NOT JVM-testable; per the
 * project's CLAUDE.md testing-scope lock-in, instrumented tests are out of
 * scope. This test covers the one piece of the exporter that IS reachable
 * from JVM tests.
 */
class SnippetExporterUriTest {
    @Test
    fun httpsUrl_routesAsRemote() {
        val route = sourceUriRoute("https://cdn.example/episode/abc.mp3")
        val remote = assertIs<SnippetSourceRoute.Remote>(route)
        assertEquals("https://cdn.example/episode/abc.mp3", remote.url)
    }

    @Test
    fun httpUrl_routesAsRemote() {
        // Legacy http feed; manifest cleartext-traffic blocks the actual
        // connection on non-localhost, but routing still picks Remote.
        val route = sourceUriRoute("http://oldschool.example/episode.mp3")
        val remote = assertIs<SnippetSourceRoute.Remote>(route)
        assertEquals("http://oldschool.example/episode.mp3", remote.url)
    }

    @Test
    fun absoluteFilePath_routesAsLocal() {
        // /data/user/0/com.kofikodr.kofipod/cache/downloads/ep1.mp3 is the
        // realistic shape — local downloads under app private storage.
        val route = sourceUriRoute("/data/cache/downloads/ep1.mp3")
        val local = assertIs<SnippetSourceRoute.Local>(route)
        assertEquals("/data/cache/downloads/ep1.mp3", local.path)
    }

    @Test
    fun relativeFilePath_routesAsLocal() {
        // Defensive — production never passes relative paths; pin that the
        // helper doesn't refuse them.
        val route = sourceUriRoute("downloads/ep1.mp3")
        val local = assertIs<SnippetSourceRoute.Local>(route)
        assertEquals("downloads/ep1.mp3", local.path)
    }

    @Test
    fun ftpScheme_routesAsLocal_currentBehavior() {
        // Current rule is "http(s) → remote; everything else → local". An
        // ftp:// URL falls through to the local path. Pin so a future
        // generalisation ("schemes with `://`" → remote) is an explicit
        // change with a matching test update.
        val route = sourceUriRoute("ftp://files.example/episode.mp3")
        assertIs<SnippetSourceRoute.Local>(route)
    }

    @Test
    fun uppercaseHttpsScheme_routesAsLocal_undercaseSensitiveCheck() {
        // The check uses `startsWith` with default case-sensitivity, so
        // "HTTPS://…" does NOT match the remote branch and is treated as a
        // file path. RFC 3986 says scheme is case-insensitive, but feeds in
        // the wild always use lowercase. Pin so a future tightening to
        // case-insensitive is an explicit, reviewed change.
        val route = sourceUriRoute("HTTPS://example.com/x.mp3")
        assertIs<SnippetSourceRoute.Local>(route)
    }

    @Test
    fun emptyString_routesAsLocal() {
        // Edge: empty input. Production would surface this as a "Export
        // failed: source not found" downstream; routing doesn't reject it
        // here — that's the exporter's job.
        val route = sourceUriRoute("")
        val local = assertIs<SnippetSourceRoute.Local>(route)
        assertEquals("", local.path)
    }
}
