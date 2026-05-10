// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReporterScrubberTest {
    @Test
    fun `scrubMessage strips query strings from URLs`() {
        val raw = "Failed to fetch https://api.podcastindex.org/podcasts?key=secret&q=foo for episode"
        val scrubbed = CrashReporterScrubber.scrubMessage(raw)
        assertEquals(
            "Failed to fetch https://api.podcastindex.org/podcasts for episode",
            scrubbed,
        )
    }

    @Test
    fun `scrubMessage handles multiple URLs in one string`() {
        val raw = "Tried https://x.com/a?b=1 then https://y.com/c?d=2"
        val scrubbed = CrashReporterScrubber.scrubMessage(raw)
        assertEquals("Tried https://x.com/a then https://y.com/c", scrubbed)
    }

    @Test
    fun `scrubMessage leaves plain text unchanged`() {
        val raw = "NullPointerException at line 42"
        assertEquals(raw, CrashReporterScrubber.scrubMessage(raw))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing gemini`() {
        val crumb =
            Breadcrumb(
                category = "http",
                message = "GET https://generativelanguage.googleapis.com/v1/models",
                data = mapOf("url" to "https://generativelanguage.googleapis.com/v1/models"),
            )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing googleapis`() {
        val crumb =
            Breadcrumb(
                category = "http",
                message = "POST https://oauth2.googleapis.com/token",
                data = emptyMap(),
            )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing podcastindex`() {
        val crumb =
            Breadcrumb(
                category = "http",
                message = "GET https://api.podcastindex.org/search?q=foo",
                data = emptyMap(),
            )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops query category breadcrumbs`() {
        val crumb =
            Breadcrumb(
                category = "query",
                message = "SELECT * FROM Episode WHERE id = ?",
                data = emptyMap(),
            )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb keeps innocuous http breadcrumbs but strips query strings in data`() {
        val crumb =
            Breadcrumb(
                category = "http",
                message = "GET https://example.com/feed?x=1",
                data = mapOf("url" to "https://example.com/feed?x=1"),
            )
        val scrubbed = CrashReporterScrubber.scrubBreadcrumb(crumb)!!
        assertEquals("GET https://example.com/feed", scrubbed.message)
        assertEquals(mapOf("url" to "https://example.com/feed"), scrubbed.data)
    }

    @Test
    fun `scrubBreadcrumb passes through non-http non-query categories unchanged`() {
        val crumb = Breadcrumb(category = "ui", message = "navigate to Library", data = emptyMap())
        assertEquals(crumb, CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    /**
     * Sentry's HTTP integration sometimes records the URL only in `data` (under
     * keys like `url`, `http.url`) while the breadcrumb `message` is just the
     * status line ("HTTP 200 OK"). The drop check must inspect data values too,
     * not only the message — otherwise a Gemini call with a generic-looking
     * status message would slip through and leak the URL.
     */
    @Test
    fun `scrubBreadcrumb drops http breadcrumb when sensitive host appears only in data values`() {
        val crumb =
            Breadcrumb(
                category = "http",
                message = "HTTP 200 OK",
                data = mapOf("url" to "https://generativelanguage.googleapis.com/v1/models"),
            )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }
}
