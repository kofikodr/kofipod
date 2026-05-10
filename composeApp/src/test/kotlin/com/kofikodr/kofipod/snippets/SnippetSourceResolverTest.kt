// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetSourceResolverTest {
    private class FakeFileChecker(private val existingPaths: Set<String>) : FileCheckerApi {
        override fun exists(path: String): Boolean = path in existingPaths
    }

    @Test
    fun `prefers local path when file exists`() {
        val r = SnippetSourceResolver(FakeFileChecker(setOf("/data/files/downloads/e1.mp3")))
        val src =
            r.resolve(
                localPath = "/data/files/downloads/e1.mp3",
                enclosureUrl = "https://x/e1.mp3",
            )
        assertEquals(SnippetSource.Local("/data/files/downloads/e1.mp3"), src)
    }

    @Test
    fun `falls back to enclosure URL when local path is blank`() {
        val r = SnippetSourceResolver(FakeFileChecker(emptySet()))
        val src = r.resolve(localPath = "", enclosureUrl = "https://x/e1.mp3")
        assertEquals(SnippetSource.Remote("https://x/e1.mp3"), src)
    }

    @Test
    fun `falls back to enclosure URL when local path is null`() {
        val r = SnippetSourceResolver(FakeFileChecker(emptySet()))
        val src = r.resolve(localPath = null, enclosureUrl = "https://x/e1.mp3")
        assertEquals(SnippetSource.Remote("https://x/e1.mp3"), src)
    }

    @Test
    fun `falls back to enclosure URL when local file does not exist`() {
        val r = SnippetSourceResolver(FakeFileChecker(emptySet()))
        val src =
            r.resolve(
                localPath = "/data/files/downloads/missing.mp3",
                enclosureUrl = "https://x/e1.mp3",
            )
        assertEquals(SnippetSource.Remote("https://x/e1.mp3"), src)
    }

    @Test
    fun `none returned when both local and remote are unavailable`() {
        val r = SnippetSourceResolver(FakeFileChecker(emptySet()))
        val src = r.resolve(localPath = null, enclosureUrl = "")
        assertEquals(SnippetSource.None, src)
    }
}
