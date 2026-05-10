// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

import com.kofikodr.kofipod.ai.AiSourceKind
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.ai.MentionedLink
import com.kofikodr.kofipod.ai.MentionedPerson
import com.kofikodr.kofipod.ai.MentionedThing
import com.kofikodr.kofipod.bookmarks.Bookmark
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.snippets.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownFormatterTest {
    private val formatter: MarkdownFormatter = MarkdownFormatterImpl()

    private val podcast =
        Podcast(
            id = "p1",
            title = "Locked On Broncos",
            author = "Locked On Network",
            description = "",
            artworkUrl = "",
            feedUrl = "https://example.com/feed",
            listId = null,
            autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 0,
            lastCheckedAt = null,
            addedAt = 0,
            primaryCategory = "Sports",
        )

    private val episode =
        Episode(
            id = "e1",
            podcastId = "p1",
            guid = "g1",
            title = "FCC bans Chinese routers",
            description = "",
            publishedAt = 0,
            durationSec = 3_600,
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )

    @Test
    fun snippetFrontmatterCarriesAllExpectedKeysInOrder() {
        val s = sampleSnippet().copy(startMs = 60_000, endMs = 120_000)
        val doc = formatter.formatSnippet(s, episode, podcast)
        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "timestampMs", "durationMs", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        val map = doc.frontmatter.toMap()
        assertEquals("snippet", map["kind"])
        assertEquals("Locked On Broncos", map["podcast"])
        assertEquals("FCC bans Chinese routers", map["episode"])
        assertEquals("https://example.com/ep1.mp3", map["episodeUrl"])
        assertEquals("60000", map["timestampMs"])
        assertEquals("60000", map["durationMs"])
        assertEquals(s.id, map["kofipodId"])
    }

    @Test
    fun snippetBodyIncludesTitleHeadingCaptionAndJumpLink() {
        val s =
            sampleSnippet().copy(
                startMs = 754_000,
                endMs = 814_000,
                title = "Best take",
                captionOverride = "Listen to the FCC announcement",
            )
        val body = formatter.formatSnippet(s, episode, podcast).body
        assertTrue(body.contains("## Best take"), "title heading missing in body: $body")
        assertTrue(body.contains("Listen to the FCC announcement"), "caption missing")
        assertTrue(body.contains("12:34"), "hms missing")
        assertTrue(body.contains(episode.enclosureUrl), "episode link missing")
    }

    @Test
    fun snippetWithoutTitleFallsBackToEpisodeTitle() {
        val s = sampleSnippet().copy(title = null)
        val body = formatter.formatSnippet(s, episode, podcast).body
        assertTrue(body.contains("## ${episode.title}"))
    }

    @Test
    fun snippetWithBlankCaptionOmitsBlockquote() {
        val s = sampleSnippet().copy(captionOverride = "   ")
        val body = formatter.formatSnippet(s, episode, podcast).body
        assertTrue(!body.contains("> "), "should not emit blockquote for blank caption")
    }

    @Test
    fun bookmarkFrontmatterCarriesAllExpectedKeysInOrder() {
        val b =
            Bookmark(
                id = "bm-7",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 754_000,
                note = "Quote: regulators caught up",
                createdAtMs = 1_700_000_000_000,
            )
        val doc = formatter.formatBookmark(b, episode, podcast)
        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "timestampMs", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        assertEquals("bookmark", doc.frontmatter.toMap()["kind"])
    }

    @Test
    fun bookmarkBodyIncludesNoteAndJumpLink() {
        val b = sampleBookmark().copy(note = "Quote: regulators caught up", timestampMs = 754_000)
        val body = formatter.formatBookmark(b, episode, podcast).body
        assertTrue(body.contains("Quote: regulators caught up"))
        assertTrue(body.contains("12:34"))
        assertTrue(body.contains(episode.enclosureUrl))
    }

    @Test
    fun bookmarkWithoutNoteOmitsNoteParagraph() {
        val b = sampleBookmark().copy(note = null, timestampMs = 754_000)
        val body = formatter.formatBookmark(b, episode, podcast).body
        assertTrue(body.contains("12:34"), "jump link still present")
        assertTrue(!body.contains("> ") && !body.contains("Quote:"), "no note paragraph")
    }

    @Test
    fun aiSummaryFrontmatterCarriesAllExpectedKeysInOrder() {
        val s = sampleAiSummary()
        val doc = formatter.formatAiSummary(s, episode, podcast)
        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        val map = doc.frontmatter.toMap()
        assertEquals("summary", map["kind"])
        // EpisodeAiSummary has no artifact-level id; the formatter synthesises
        // "summary-<episodeId>" so Slice 6 destination adapters get a stable
        // upsert key. See KDoc on MarkdownFormatter for the contract.
        assertEquals("summary-${episode.id}", map["kofipodId"])
    }

    @Test
    fun snippetWithDegenerateRangeProducesNonNegativeDurationMs() {
        // Defence-in-depth: if a corrupt DB row had endMs < startMs, the formatter
        // must use Snippet.durationMs's coerceAtLeast(0L) guard rather than emitting
        // a negative integer that downstream YAML parsers would accept silently.
        val s = sampleSnippet().copy(startMs = 100_000, endMs = 0)
        val doc = formatter.formatSnippet(s, episode, podcast)
        assertEquals("0", doc.frontmatter.toMap()["durationMs"])
    }

    @Test
    fun aiSummaryBodyIncludesProseAndPopulatedSections() {
        val s =
            sampleAiSummary().copy(
                summary = "Headline summary.",
                people = listOf(MentionedPerson(name = "Sarah", subtitle = "FCC commissioner")),
                things = listOf(MentionedThing(name = "TP-Link AC1750", subtitle = "consumer router")),
                links = listOf(MentionedLink(label = "FCC PDF", url = "https://fcc.gov/x.pdf")),
            )
        val body = formatter.formatAiSummary(s, episode, podcast).body
        assertTrue(body.contains("Headline summary."))
        assertTrue(body.contains("## People"))
        assertTrue(body.contains("- Sarah — FCC commissioner"))
        assertTrue(body.contains("## Things"))
        assertTrue(body.contains("- TP-Link AC1750 — consumer router"))
        assertTrue(body.contains("## Links"))
        assertTrue(body.contains("[FCC PDF](https://fcc.gov/x.pdf)"))
    }

    @Test
    fun aiSummaryWithEmptySubtitleOmitsDash() {
        // MentionedPerson.subtitle defaults to "" — when blank, body should be just "- Sarah"
        // (no em-dash trailing).
        val s =
            sampleAiSummary().copy(
                people = listOf(MentionedPerson(name = "Sarah")),
            )
        val body = formatter.formatAiSummary(s, episode, podcast).body
        assertTrue(body.contains("- Sarah"))
        assertTrue(!body.contains("Sarah —"), "blank subtitle should not produce trailing dash")
    }

    @Test
    fun aiSummaryWithEmptyExtrasOmitsEmptySections() {
        val s = sampleAiSummary().copy(people = emptyList(), things = emptyList(), links = emptyList())
        val body = formatter.formatAiSummary(s, episode, podcast).body
        assertTrue(!body.contains("## People"))
        assertTrue(!body.contains("## Things"))
        assertTrue(!body.contains("## Links"))
    }

    @Test
    fun snippetFilenameUsesSlugAndShortIdAndEndsWithMd() {
        val s = sampleSnippet().copy(id = "snip-mt29-abcdef", title = "Best!! Take")
        val doc = formatter.formatSnippet(s, episode, podcast)
        assertTrue(doc.filename.endsWith(".md"))
        assertTrue(doc.filename.contains("locked-on-broncos"))
        assertTrue(doc.filename.contains("best-take"))
        assertTrue(doc.filename.contains("snippet"))
    }

    private fun sampleSnippet() =
        Snippet(
            id = "snip-mt29",
            episodeId = "e1",
            podcastId = "p1",
            startMs = 754_000,
            endMs = 814_000,
            title = "Best take",
            captionOverride = null,
            createdAtMs = 1_700_000_000_000,
            lastExportFormat = null,
            lastExportPath = null,
        )

    private fun sampleBookmark() =
        Bookmark(
            id = "bm-7",
            episodeId = "e1",
            podcastId = "p1",
            timestampMs = 754_000,
            note = null,
            createdAtMs = 1_700_000_000_000,
        )

    private fun sampleAiSummary() =
        AiSummary(
            episodeId = "e1",
            generatedAtMs = 1_700_000_000_000,
            modelId = "gemini-1.5-flash",
            sourceKind = AiSourceKind.Transcript,
            sourceFingerprint = "fp",
            summary = "",
            people = emptyList(),
            things = emptyList(),
            links = emptyList(),
        )
}
