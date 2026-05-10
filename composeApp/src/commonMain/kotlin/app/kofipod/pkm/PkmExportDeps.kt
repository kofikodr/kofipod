// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.snippets.Snippet

/**
 * Per-feature seam over the five repositories the [PkmExportCoordinator] resolves
 * its domain types from. Concrete repos are final classes (not interfaces), so a
 * thin seam keeps the coordinator unit-testable without pulling in a mocking
 * framework. Mirrors the shape of [app.kofipod.snippets.CaptionDeps] introduced
 * in Slice 4.
 *
 * Production binding (see `CommonModule.kt`, wired in Task 10) is a one-line
 * adapter that delegates each call to the matching repository:
 *  - [snippetById]   -> `SnippetRepository.selectById`
 *  - [bookmarkById]  -> `BookmarkRepository.selectById`
 *  - [summaryFor]    -> `AiSummaryRepository.cachedNow`
 *  - [episode]       -> `EpisodesRepository.episodeNow` (synchronous)
 *  - [podcast]       -> `LibraryRepository.podcastNow`  (synchronous)
 *
 * The synchronous methods are exposed un-suspended on purpose — they execute
 * `executeAsOneOrNull` under the hood and run on the caller's coroutine
 * context (the named `"appScope"` uses `Dispatchers.Default`).
 */
interface PkmExportDeps {
    suspend fun snippetById(id: String): Snippet?

    suspend fun bookmarkById(id: String): Bookmark?

    suspend fun summaryFor(episodeId: String): AiSummary?

    fun episode(id: String): Episode?

    fun podcast(id: String): Podcast?
}
