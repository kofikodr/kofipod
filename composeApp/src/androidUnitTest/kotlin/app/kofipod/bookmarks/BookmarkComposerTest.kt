// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookmarkComposerTest {
    @Test
    fun requestQuickAdd_emitsVisibleStateWithSnapshot() {
        val composer = BookmarkComposer()

        composer.requestQuickAdd(
            episodeId = "ep-1",
            podcastId = "pod-1",
            episodeTitle = "Episode A",
            podcastTitle = "Show A",
            timestampMs = 90_000L,
        )

        val state = composer.state.value
        assertTrue(state is BookmarkComposerState.Visible)
        assertEquals("ep-1", state.episodeId)
        assertEquals(90_000L, state.timestampMs)
        assertEquals("Show A", state.podcastTitle)
    }

    @Test
    fun cancel_returnsToHidden() {
        val composer = BookmarkComposer()
        composer.requestQuickAdd("ep", "pod", "et", "pt", 0L)
        composer.cancel()
        assertEquals(BookmarkComposerState.Hidden, composer.state.value)
    }

    @Test
    fun secondRequest_replacesSnapshot_lastWriteWins() {
        val composer = BookmarkComposer()
        composer.requestQuickAdd("ep-1", "pod-1", "First", "Show", 100L)
        composer.requestQuickAdd("ep-2", "pod-2", "Second", "Other Show", 200L)

        val state = composer.state.value
        assertTrue(state is BookmarkComposerState.Visible)
        assertEquals("ep-2", state.episodeId)
        assertEquals(200L, state.timestampMs)
        assertEquals("Other Show", state.podcastTitle)
    }
}
