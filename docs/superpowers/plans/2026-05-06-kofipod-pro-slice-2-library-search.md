# Kofipod Pro — Slice 2 (Library FTS5 Search) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Pro-gated full-text search bar to the Library screen, indexing bookmark notes, AI summaries, and (newly cached) transcripts via SQLite FTS5. Result rows tap through to the source surface.

**Architecture:** A single SQLite FTS5 virtual table (`LibrarySearchIndex`) collects rows from three content tables — `Bookmark`, `EpisodeAiSummary`, and a new `TranscriptCache`. SQL triggers keep the index in sync on insert/update/delete; a one-shot migration backfills existing rows. Transcript text is captured by hooking the existing `AiSummaryRepository.runTranscript` (zero-cost — the text was already fetched, just discarded after generating the summary). A new `LibrarySearchRepository` exposes a Flow-based `search(rawQuery): Flow<List<LibrarySearchResult>>`; a sanitiser turns the user's free-form input into a safe FTS5 prefix query. The Library screen gains a top-of-screen search bar that mirrors the Slice-1 Bookmarks Pro-gate pattern: `Pro` users navigate to `LibrarySearchScreen`; `Free`/`Unknown` users open the Paywall sheet via `paywallRouter.requestPaywall("paywall_library_search")`. Snippet indexing is **deferred to Slice 3** — the `Snippet` table doesn't exist yet, so no placeholder triggers are wired now.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight 2.0.2 (default SQLite dialect — already parses FTS5 + `MATCH` syntax in `.sq` files), Koin DI, Material 3 search field, kotlinx.coroutines Flow + `debounce`, kotlinx.datetime.

---

## File structure

### New files

- `composeApp/src/commonMain/sqldelight/app/kofipod/db/TranscriptCache.sq` — table + queries for cached transcript text, keyed by episodeId.
- `composeApp/src/commonMain/sqldelight/app/kofipod/db/LibrarySearchIndex.sq` — FTS5 virtual table + sync triggers + the parameterised `MATCH` select.
- `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/17.sqm` — schema bump: add `TranscriptCache`, the FTS5 virtual table, the triggers, and backfill existing `Bookmark` + `EpisodeAiSummary` rows into the index.
- `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchResult.kt` — sealed result type (`BookmarkMatch`, `SummaryMatch`, `TranscriptMatch`) + shared metadata (episode/podcast titles, artwork, excerpt with match markers).
- `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchKind.kt` — enum mapping the SQL `kind` column (`bookmark`/`summary`/`transcript`) to typed values; lives next to the result type to keep the `kind` literal centralised.
- `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchQuery.kt` — pure sanitiser: turn raw user input into an FTS5-safe phrase + prefix expression. No deps; trivially unit-testable.
- `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchRepository.kt` — `search(rawQuery): Flow<List<LibrarySearchResult>>`. Empty/blank input emits `emptyList()` without hitting SQLite.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchScreen.kt` — full-screen search experience.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchViewModel.kt` — debounced query flow → repo, optional kind filter.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchRow.kt` — typed row composable (kind chip + excerpt + episode/podcast subtitle).
- `composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchQueryTest.kt` — unit tests over the sanitiser.
- `composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchRepositoryTest.kt` — in-memory DB tests covering insert/update/delete sync + ranking sanity.
- (No new file — the AiSummaryRepository transcript-cache test is added to the existing `AiSummaryRepositoryTest.kt`; see Task 7.)

### Modified files

- `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt` — bump `DB_SCHEMA_VERSION` from 16 to 17.
- `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt` — after a successful transcript fetch, persist the text via the new `TranscriptCache` upsert. Single line + injected query handle.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt` — add `Route.LibrarySearch`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` — wire `composable<Route.LibrarySearch>`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt` — add a search-bar entry-point row (Pro-gated tap) above the existing folder grid, below the section header. Match the existing Bookmarks-row spacing.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` — add `onLibrarySearchTapped(): Boolean` mirroring `onBookmarksTapped()`. New paywall trigger key: `paywall_library_search`.
- `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — bind `LibrarySearchRepository` as a singleton; add `viewModel { LibrarySearchViewModel(get()) }`. (No new ctor param on `AiSummaryRepository`: `db: KofipodDatabase` is already injected and exposes `transcriptCacheQueries` directly.)

### Touched but NOT modifying behaviour

- `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt` — no edit; the FTS triggers handle sync at the SQL layer.
- `composeApp/src/androidMain/res/xml/backup_rules.xml` / `backup_rules_legacy.xml` — no edit. The whole DB file already rides along; `TranscriptCache` is publisher content and non-sensitive.

---

## Conventions worth re-reading before starting

- `composeApp/CLAUDE.md` — "iOS compile must stay green" (no `java.*`, no `androidx.*` in `commonMain`); the `viewModel { ... }` factory parity rule (any new ctor dep means the factory must change in the same commit); the "Tab strip stays four max" rule (irrelevant here, but the analogous "Library home stays uncluttered" judgement applies — one search row, not a redesign).
- The existing Pro-gate pattern in `LibraryViewModel.onBookmarksTapped()` (lines 86–95) is the contract; mirror it verbatim for `onLibrarySearchTapped()`. The trigger key follows `paywall_<surface>` convention (we already have `paywall_bookmark`, `paywall_snip`).
- Migrations: one `.sqm` file per schema bump; never edit existing tables. Current schema version is **16**, so this slice ends at **17**.
- SQLDelight 2.0.2 with the default SQLite dialect parses `CREATE VIRTUAL TABLE ... USING fts5(...)` and `WHERE x MATCH :q`. No new dialect dep needed. If schema generation rejects something during `:composeApp:compileDebugKotlinAndroid`, the fallback is to keep the DDL in the migration only and execute the search SELECT via `db.driver.executeQuery(...)` from `LibrarySearchRepository` — but only fall back if SQLDelight actually fails; do not preemptively skip typed queries.
- Tests: per CLAUDE.md, "all tests must pass before declaring work done." Run the green-check sequence at the end of each task: `ktlintFormat`, `detekt`, `compilePlayDebugKotlinAndroid`, `compileFossDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`, `testPlayDebugUnitTest`, `testFossDebugUnitTest`. Slice-end emulator verify uses `Pixel_9a` and the FOSS flavor (Pro is unconditionally granted).
- The user-level rule "tests must be audited by the test-quality-auditor before they run" applies to every test task in this slice. Audit happens after the test is written and before the implementation that makes it pass — same rhythm as Slice 1.

---

## Task 1: Domain types — search results, kind enum, sanitiser shape

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchKind.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchResult.kt`

- [ ] **Step 1: Create the kind enum**

`composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchKind.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

/**
 * Centralised mapping between the SQL `kind` column literal stored in the FTS5
 * `LibrarySearchIndex` table and the typed value used in Kotlin. Keep the
 * [wire] strings stable — they live inside SQL triggers, so changing them
 * means writing another migration.
 */
enum class LibrarySearchKind(val wire: String) {
    Bookmark("bookmark"),
    Summary("summary"),
    Transcript("transcript"),
    ;

    companion object {
        fun fromWire(value: String): LibrarySearchKind? = entries.firstOrNull { it.wire == value }
    }
}
```

- [ ] **Step 2: Create the result sealed type**

`composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchResult.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

/**
 * One row in the Library search result list.
 *
 * Every variant carries enough metadata to render a row without a follow-up
 * query (episode + podcast titles, artwork) and enough routing info to deep
 * link the user to the source surface (episodeId always; timestampMs only
 * where a seek-to-position is meaningful).
 *
 * [excerpt] is FTS5's `snippet(...)` output — short text with `<<…>>` markers
 * around matched terms. The UI strips/replaces those markers when rendering.
 */
sealed interface LibrarySearchResult {
    val episodeId: String
    val episodeTitle: String
    val podcastId: String
    val podcastTitle: String
    val artworkUrl: String
    val excerpt: String

    data class BookmarkMatch(
        val bookmarkId: String,
        val timestampMs: Long,
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult

    data class SummaryMatch(
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult

    data class TranscriptMatch(
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult
}
```

- [ ] **Step 3: Verify ktlint + detekt are green**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchKind.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchResult.kt
git commit -m "slice2(search): add LibrarySearchKind + LibrarySearchResult sealed type"
```

---

## Task 2: TranscriptCache schema + queries

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/TranscriptCache.sq`

- [ ] **Step 1: Create the schema file**

`composeApp/src/commonMain/sqldelight/app/kofipod/db/TranscriptCache.sq`:

```sql
-- Slice 2 (Pro Library Search): cached transcript text per episode.
--
-- The text is captured opportunistically by AiSummaryRepository.runTranscript
-- — it was already fetched there to feed Gemini, so persisting it costs only
-- the disk write. The entire row's purpose is to give the LibrarySearchIndex
-- FTS triggers something to mirror: transcripts are how users find episodes
-- by topic. fetchedAtMs is informational (no TTL — transcripts are immutable
-- once published).

CREATE TABLE TranscriptCache (
    episodeId    TEXT NOT NULL PRIMARY KEY,
    text         TEXT NOT NULL,
    fetchedAtMs  INTEGER NOT NULL,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE
);

upsert:
INSERT OR REPLACE INTO TranscriptCache (episodeId, text, fetchedAtMs)
VALUES (?, ?, ?);

selectByEpisode:
SELECT * FROM TranscriptCache WHERE episodeId = ?;

deleteByEpisode:
DELETE FROM TranscriptCache WHERE episodeId = ?;

deleteAll:
DELETE FROM TranscriptCache;
```

- [ ] **Step 2: Verify SQLDelight code-gen accepts the schema**

Run: `./gradlew :composeApp:generateCommonMainKofipodDatabaseInterface`
Expected: BUILD SUCCESSFUL. The generated `KofipodDatabase` now exposes `transcriptCacheQueries`.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/TranscriptCache.sq
git commit -m "slice2(search): add TranscriptCache table for opportunistic transcript persistence"
```

---

## Task 3: LibrarySearchIndex FTS5 virtual table, triggers, and search query

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/LibrarySearchIndex.sq`

- [ ] **Step 1: Create the schema file**

`composeApp/src/commonMain/sqldelight/app/kofipod/db/LibrarySearchIndex.sq`:

```sql
-- Slice 2 (Pro Library Search): a single FTS5 virtual table that aggregates
-- searchable text from THREE content tables — Bookmark.note,
-- EpisodeAiSummary.summary, and TranscriptCache.text — under a typed `kind`
-- discriminator so one query can return mixed result rows.
--
-- We intentionally do NOT use FTS5's `content=` option (external content
-- table) because we need to mirror three tables, not one. Keeping the index
-- self-contained and synced by triggers is the simpler shape.
--
-- `episodeId` is materialised so we can JOIN to Episode + Podcast at query
-- time without going back through the source row's primary key.
-- `timestampMs` is meaningful for bookmark hits (seek target); zero for
-- summary / transcript hits (the row's per-episode kind already implies the
-- jump-to-detail intent).
--
-- Snippet indexing arrives in Slice 3 — a new trigger set will add
-- `kind='snippet'` rows. No placeholder triggers here; we don't wire dead
-- code.

CREATE VIRTUAL TABLE LibrarySearchIndex USING fts5(
    kind UNINDEXED,
    itemId UNINDEXED,
    episodeId UNINDEXED,
    timestampMs UNINDEXED,
    text,
    tokenize = 'porter unicode61 remove_diacritics 2'
);

-- ── Bookmark sync ──────────────────────────────────────────────────────────
CREATE TRIGGER bookmark_fts_ai AFTER INSERT ON Bookmark
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('bookmark', new.id, new.episodeId, new.timestampMs, COALESCE(new.note, ''));
END;

CREATE TRIGGER bookmark_fts_au AFTER UPDATE ON Bookmark
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'bookmark' AND itemId = old.id;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('bookmark', new.id, new.episodeId, new.timestampMs, COALESCE(new.note, ''));
END;

CREATE TRIGGER bookmark_fts_ad AFTER DELETE ON Bookmark
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'bookmark' AND itemId = old.id;
END;

-- ── EpisodeAiSummary sync ──────────────────────────────────────────────────
CREATE TRIGGER summary_fts_ai AFTER INSERT ON EpisodeAiSummary
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('summary', new.episodeId, new.episodeId, 0, new.summary);
END;

CREATE TRIGGER summary_fts_au AFTER UPDATE ON EpisodeAiSummary
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'summary' AND itemId = old.episodeId;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('summary', new.episodeId, new.episodeId, 0, new.summary);
END;

CREATE TRIGGER summary_fts_ad AFTER DELETE ON EpisodeAiSummary
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'summary' AND itemId = old.episodeId;
END;

-- ── TranscriptCache sync ───────────────────────────────────────────────────
CREATE TRIGGER transcript_fts_ai AFTER INSERT ON TranscriptCache
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('transcript', new.episodeId, new.episodeId, 0, new.text);
END;

CREATE TRIGGER transcript_fts_au AFTER UPDATE ON TranscriptCache
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'transcript' AND itemId = old.episodeId;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('transcript', new.episodeId, new.episodeId, 0, new.text);
END;

CREATE TRIGGER transcript_fts_ad AFTER DELETE ON TranscriptCache
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'transcript' AND itemId = old.episodeId;
END;

-- ── Queries ────────────────────────────────────────────────────────────────

-- Mixed-kind ranked search. JOINs Episode + Podcast at query time so one
-- result row carries every column the UI needs.
--
-- `snippet(LibrarySearchIndex, 4, ...)` extracts excerpts from column 4
-- (zero-indexed: kind=0, itemId=1, episodeId=2, timestampMs=3, text=4) with
-- ≤ 12 tokens around the match, '<<' / '>>' wrappers, and '…' ellipsis.
search:
SELECT
    fts.kind          AS kind,
    fts.itemId        AS itemId,
    fts.episodeId     AS episodeId,
    fts.timestampMs   AS timestampMs,
    snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
    e.title           AS episodeTitle,
    p.id              AS podcastId,
    p.title           AS podcastTitle,
    p.artworkUrl      AS artworkUrl
FROM LibrarySearchIndex fts
INNER JOIN Episode e ON e.id = fts.episodeId
INNER JOIN Podcast p ON p.id = e.podcastId
WHERE LibrarySearchIndex MATCH ?
ORDER BY rank
LIMIT 100;

-- Same shape, kind-filtered. Used when the user taps a kind chip.
searchByKind:
SELECT
    fts.kind          AS kind,
    fts.itemId        AS itemId,
    fts.episodeId     AS episodeId,
    fts.timestampMs   AS timestampMs,
    snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
    e.title           AS episodeTitle,
    p.id              AS podcastId,
    p.title           AS podcastTitle,
    p.artworkUrl      AS artworkUrl
FROM LibrarySearchIndex fts
INNER JOIN Episode e ON e.id = fts.episodeId
INNER JOIN Podcast p ON p.id = e.podcastId
WHERE LibrarySearchIndex MATCH ?
  AND fts.kind = ?
ORDER BY rank
LIMIT 100;
```

- [ ] **Step 2: Verify SQLDelight code-gen accepts FTS5 + MATCH**

Run: `./gradlew :composeApp:generateCommonMainKofipodDatabaseInterface`
Expected: BUILD SUCCESSFUL. The generated `KofipodDatabase` exposes `librarySearchIndexQueries.search(query)` and `searchByKind(query, kind)`.

If this fails because SQLDelight can't parse `MATCH`, the fallback is documented in "Conventions" above — leave the SELECT queries OUT of `LibrarySearchIndex.sq` (DDL only) and execute them via `db.driver.executeQuery(...)` from `LibrarySearchRepository`. Do not change to that path unless code-gen actually rejects the file.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/LibrarySearchIndex.sq
git commit -m "slice2(search): add LibrarySearchIndex FTS5 virtual table + sync triggers"
```

---

## Task 4: Migration 17 — install schema + backfill existing rows

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/17.sqm`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt:70` — bump `DB_SCHEMA_VERSION` from 16 to 17.

- [ ] **Step 1: Create the migration**

`composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/17.sqm`:

```sql
-- Slice 2 (Pro Library Search): introduce TranscriptCache + the
-- LibrarySearchIndex FTS5 virtual table + the nine triggers that keep the
-- index synced with Bookmark / EpisodeAiSummary / TranscriptCache.
--
-- Backfill at the end: existing Bookmark + EpisodeAiSummary rows are seeded
-- into the index so already-saved bookmarks and already-cached summaries are
-- searchable on first launch after upgrade. TranscriptCache is brand new
-- and has nothing to backfill.

CREATE TABLE TranscriptCache (
    episodeId    TEXT NOT NULL PRIMARY KEY,
    text         TEXT NOT NULL,
    fetchedAtMs  INTEGER NOT NULL,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE LibrarySearchIndex USING fts5(
    kind UNINDEXED,
    itemId UNINDEXED,
    episodeId UNINDEXED,
    timestampMs UNINDEXED,
    text,
    tokenize = 'porter unicode61 remove_diacritics 2'
);

CREATE TRIGGER bookmark_fts_ai AFTER INSERT ON Bookmark
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('bookmark', new.id, new.episodeId, new.timestampMs, COALESCE(new.note, ''));
END;

CREATE TRIGGER bookmark_fts_au AFTER UPDATE ON Bookmark
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'bookmark' AND itemId = old.id;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('bookmark', new.id, new.episodeId, new.timestampMs, COALESCE(new.note, ''));
END;

CREATE TRIGGER bookmark_fts_ad AFTER DELETE ON Bookmark
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'bookmark' AND itemId = old.id;
END;

CREATE TRIGGER summary_fts_ai AFTER INSERT ON EpisodeAiSummary
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('summary', new.episodeId, new.episodeId, 0, new.summary);
END;

CREATE TRIGGER summary_fts_au AFTER UPDATE ON EpisodeAiSummary
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'summary' AND itemId = old.episodeId;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('summary', new.episodeId, new.episodeId, 0, new.summary);
END;

CREATE TRIGGER summary_fts_ad AFTER DELETE ON EpisodeAiSummary
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'summary' AND itemId = old.episodeId;
END;

CREATE TRIGGER transcript_fts_ai AFTER INSERT ON TranscriptCache
BEGIN
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('transcript', new.episodeId, new.episodeId, 0, new.text);
END;

CREATE TRIGGER transcript_fts_au AFTER UPDATE ON TranscriptCache
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'transcript' AND itemId = old.episodeId;
    INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
    VALUES ('transcript', new.episodeId, new.episodeId, 0, new.text);
END;

CREATE TRIGGER transcript_fts_ad AFTER DELETE ON TranscriptCache
BEGIN
    DELETE FROM LibrarySearchIndex WHERE kind = 'transcript' AND itemId = old.episodeId;
END;

-- Backfill existing rows. INSERT triggers do NOT fire for INSERTs into the
-- FTS table itself, so this is a single transactional block with the schema
-- creation above (SQLDelight wraps each .sqm in a transaction by default).
INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
SELECT 'bookmark', id, episodeId, timestampMs, COALESCE(note, '')
FROM Bookmark;

INSERT INTO LibrarySearchIndex (kind, itemId, episodeId, timestampMs, text)
SELECT 'summary', episodeId, episodeId, 0, summary
FROM EpisodeAiSummary;
```

- [ ] **Step 2: Bump the backup manifest schema version**

Edit `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt`:

```kotlin
const val DB_SCHEMA_VERSION = 17
```

(Was 16.)

- [ ] **Step 3: Verify schema generation across all flavors + iOS**

Run: `./gradlew :composeApp:generateCommonMainKofipodDatabaseInterface :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/17.sqm \
        composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt
git commit -m "slice2(search): migration 17 — TranscriptCache + LibrarySearchIndex FTS5 + backfill"
```

---

## Task 5: Query sanitiser (FTS5 prefix-safe)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchQuery.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchQueryTest.kt`

The user types free-form text. We need to convert that into an FTS5 expression that:
1. Treats each whitespace-delimited token as a prefix (`foo*`) so partial matches work as the user types.
2. Quote-escapes any token containing FTS5 punctuation (`" ' * - ^ / : ( )`) so a searched phrase like `it's` doesn't get parsed as a syntax error.
3. Treats blank input as a "no query" sentinel — the repo emits an empty list rather than executing.

- [ ] **Step 1: Write the failing test**

`composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchQueryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrarySearchQueryTest {
    @Test
    fun `blank input returns null`() {
        assertNull(LibrarySearchQuery.toFtsExpression(""))
        assertNull(LibrarySearchQuery.toFtsExpression("   "))
        assertNull(LibrarySearchQuery.toFtsExpression("\t\n "))
    }

    @Test
    fun `single word becomes prefix match`() {
        assertEquals("\"learning\"*", LibrarySearchQuery.toFtsExpression("learning"))
    }

    @Test
    fun `multiple words become AND of prefix matches`() {
        assertEquals(
            "\"continual\"* \"learning\"*",
            LibrarySearchQuery.toFtsExpression("continual learning"),
        )
    }

    @Test
    fun `embedded double quote is escaped by doubling`() {
        // FTS5 string literals double-quote-escape: "foo""bar"
        assertEquals(
            "\"it\"\"s\"*",
            LibrarySearchQuery.toFtsExpression("it\"s"),
        )
    }

    @Test
    fun `apostrophes and dashes survive without breaking the parser`() {
        // We don't strip — we let FTS5 tokenize them. Quoting is enough.
        assertEquals(
            "\"it's\"* \"co-pilot\"*",
            LibrarySearchQuery.toFtsExpression("it's co-pilot"),
        )
    }

    @Test
    fun `leading and trailing whitespace is trimmed before splitting`() {
        assertEquals("\"foo\"*", LibrarySearchQuery.toFtsExpression("   foo   "))
    }

    @Test
    fun `internal whitespace runs collapse to a single token boundary`() {
        assertEquals(
            "\"foo\"* \"bar\"*",
            LibrarySearchQuery.toFtsExpression("foo     bar"),
        )
    }
}
```

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.search.LibrarySearchQueryTest"`
Expected: FAIL with "Unresolved reference: LibrarySearchQuery".

- [ ] **Step 2: Implement the sanitiser**

`composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchQuery.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

/**
 * Turn raw user input from the Library search bar into an FTS5 expression.
 *
 * Rules:
 *   - Blank → `null` (caller emits an empty result list without hitting SQLite).
 *   - Each whitespace-delimited token is wrapped as an FTS5 string literal
 *     (double-quoted, with embedded `"` doubled) and given a `*` prefix
 *     suffix so partial matches show up as the user types.
 *   - Multiple tokens are space-joined → FTS5 implicit AND.
 *
 * Why not strip punctuation: FTS5's `unicode61` tokenizer already handles
 * apostrophes / dashes correctly inside quoted literals. Stripping them
 * would lose phrases like `"it's"` or `"co-pilot"`.
 */
object LibrarySearchQuery {
    fun toFtsExpression(raw: String): String? {
        val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
```

- [ ] **Step 3: Run the tests until they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.search.LibrarySearchQueryTest"`
Expected: PASS (7 tests).

- [ ] **Step 4: Test audit**

Dispatch the `test-quality-auditor` subagent on `LibrarySearchQueryTest.kt` per the standing project rule (CLAUDE.md "Tests and testing and more tests"). Address any critical / high issues raised. Re-run tests until clean.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchQuery.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchQueryTest.kt
git commit -m "slice2(search): FTS5-safe query sanitiser + tests"
```

---

## Task 6: LibrarySearchRepository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchRepository.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

The test uses the existing in-memory test driver pattern (see `BookmarkRepositoryTest.kt` for the recipe — JDBC SQLite driver + `Schema.create`). Insert a Podcast + Episode + a Bookmark, then assert search hits.

`composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarySearchRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var db: KofipodDatabase
    private lateinit var repo: LibrarySearchRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        db = KofipodDatabase(driver)
        repo = LibrarySearchRepository(db = db)

        // Seed: one podcast, one episode.
        db.podcastQueries.insertReplace(
            id = "p1", title = "AI Show", author = "A", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 0,
            primaryCategory = null,
        )
        db.episodeQueries.insertReplace(
            id = "e1", podcastId = "p1", guid = "g", title = "Why continual learning matters",
            description = "", publishedAt = 0, durationSec = 1000, enclosureUrl = "",
            enclosureMimeType = null, fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = null, chaptersUrl = null, transcriptUrl = null,
        )
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun `bookmark insert is searchable by note`() = runTest {
        db.bookmarkQueries.insert(
            id = "b1", episodeId = "e1", podcastId = "p1",
            timestampMs = 5_000L, note = "great quote about learning", createdAtMs = 1L,
        )

        val results = repo.search("learning").first()
        assertEquals(1, results.size)
        val hit = results.single() as LibrarySearchResult.BookmarkMatch
        assertEquals("b1", hit.bookmarkId)
        assertEquals(5_000L, hit.timestampMs)
        assertTrue(hit.excerpt.contains("learning", ignoreCase = true))
    }

    @Test
    fun `summary insert is searchable by content`() = runTest {
        db.episodeAiSummaryQueries.upsert(
            episodeId = "e1", generatedAtMs = 0, modelId = "m",
            sourceKind = "transcript", sourceFingerprint = "fp",
            summary = "A discussion of continual learning in language models.",
            peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
        )

        val results = repo.search("continual").first()
        assertEquals(1, results.size)
        assertTrue(results.single() is LibrarySearchResult.SummaryMatch)
    }

    @Test
    fun `transcript insert is searchable and update replaces old text`() = runTest {
        db.transcriptCacheQueries.upsert(episodeId = "e1", text = "hello banana world", fetchedAtMs = 1)

        val before = repo.search("banana").first()
        assertEquals(1, before.size)
        assertTrue(before.single() is LibrarySearchResult.TranscriptMatch)

        db.transcriptCacheQueries.upsert(episodeId = "e1", text = "hello cherry world", fetchedAtMs = 2)
        val afterBanana = repo.search("banana").first()
        val afterCherry = repo.search("cherry").first()
        assertEquals(0, afterBanana.size)
        assertEquals(1, afterCherry.size)
    }

    @Test
    fun `bookmark delete removes the index row`() = runTest {
        db.bookmarkQueries.insert(
            id = "b1", episodeId = "e1", podcastId = "p1",
            timestampMs = 0, note = "uniquephrase", createdAtMs = 0,
        )
        assertEquals(1, repo.search("uniquephrase").first().size)

        db.bookmarkQueries.deleteById("b1")
        assertEquals(0, repo.search("uniquephrase").first().size)
    }

    @Test
    fun `episode cascade deletes all index rows for that episode`() = runTest {
        db.bookmarkQueries.insert(
            id = "b1", episodeId = "e1", podcastId = "p1",
            timestampMs = 0, note = "alpha", createdAtMs = 0,
        )
        db.episodeAiSummaryQueries.upsert(
            episodeId = "e1", generatedAtMs = 0, modelId = "m",
            sourceKind = "transcript", sourceFingerprint = "fp",
            summary = "alpha beta", peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
        )
        db.transcriptCacheQueries.upsert(episodeId = "e1", text = "alpha gamma", fetchedAtMs = 0)
        assertEquals(3, repo.search("alpha").first().size)

        // Delete the episode — Bookmark + EpisodeAiSummary + TranscriptCache cascade,
        // and their DELETE triggers fire to clear LibrarySearchIndex.
        db.episodeQueries.deleteById("e1")
        assertEquals(0, repo.search("alpha").first().size)
    }

    @Test
    fun `blank query emits empty without hitting SQLite`() = runTest {
        db.bookmarkQueries.insert(
            id = "b1", episodeId = "e1", podcastId = "p1",
            timestampMs = 0, note = "would-match", createdAtMs = 0,
        )
        assertEquals(0, repo.search("").first().size)
        assertEquals(0, repo.search("   ").first().size)
    }

    @Test
    fun `kind filter narrows to a single bucket`() = runTest {
        db.bookmarkQueries.insert(
            id = "b1", episodeId = "e1", podcastId = "p1",
            timestampMs = 0, note = "shared word", createdAtMs = 0,
        )
        db.episodeAiSummaryQueries.upsert(
            episodeId = "e1", generatedAtMs = 0, modelId = "m",
            sourceKind = "transcript", sourceFingerprint = "fp",
            summary = "shared word", peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
        )
        assertEquals(2, repo.search("shared").first().size)
        val onlyBookmarks = repo.search("shared", kind = LibrarySearchKind.Bookmark).first()
        assertEquals(1, onlyBookmarks.size)
        assertTrue(onlyBookmarks.single() is LibrarySearchResult.BookmarkMatch)
    }
}
```

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.search.LibrarySearchRepositoryTest"`
Expected: FAIL with "Unresolved reference: LibrarySearchRepository".

- [ ] **Step 2: Implement the repository**

> **IMPORTANT — Task 3 fallback in effect.** The SQLDelight 2.0.2 default SQLite 3.18 dialect rejected `ORDER BY rank` and `snippet(...)`, so the two named SELECT queries are NOT in `LibrarySearchIndex.sq` (the SQL is preserved as comments at the bottom of that file). The repository must execute raw SQL via `db.driver.executeQuery(...)`. We pay the price of manual row mapping; in exchange we keep typed Kotlin types at the call site.
>
> `Flow` reactivity comes from `db.driver.notifyListeners(...)` — but for the search use-case we don't need it: queries change as the user types, and we don't expect the underlying tables to mutate while the user is reading results. So `search()` returns a one-shot `flow { emit(execute()) }` rather than reactive. If a future feature needs reactivity, we can introduce `db.driver.addListener(["LibrarySearchIndex"]) { ... }` then.

`composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

class LibrarySearchRepository(
    private val db: KofipodDatabase,
) {
    /**
     * Search across bookmark notes, AI summaries, and cached transcripts.
     *
     * Blank [rawQuery] short-circuits to `flowOf(emptyList())` — we never
     * issue a SQL query for an empty search box.
     *
     * [kind] limits results to one bucket (used by chip-filter UI). `null`
     * returns mixed-kind results.
     *
     * Implementation note: the SELECT goes through `db.driver.executeQuery`
     * with a hand-written cursor mapper because SQLDelight 2.0.2 cannot parse
     * FTS5 `ORDER BY rank` + `snippet(...)`. See LibrarySearchIndex.sq's
     * comment block for the canonical SQL.
     */
    fun search(
        rawQuery: String,
        kind: LibrarySearchKind? = null,
    ): Flow<List<LibrarySearchResult>> {
        val expression = LibrarySearchQuery.toFtsExpression(rawQuery) ?: return flowOf(emptyList())
        return flow { emit(executeSearch(expression, kind)) }.flowOn(Dispatchers.Default)
    }

    private fun executeSearch(expression: String, kind: LibrarySearchKind?): List<LibrarySearchResult> {
        val sql = if (kind == null) SQL_SEARCH else SQL_SEARCH_BY_KIND
        val parameterCount = if (kind == null) 1 else 2
        val rows = mutableListOf<LibrarySearchResult>()
        // QueryResult.AsyncValue has `await`; QueryResult.Value is sync. Use the
        // `Value` form by passing identifier=null + cache=false. The driver returns
        // QueryResult<R> where R is whatever the mapper returns.
        db.driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    val typed = LibrarySearchKind.fromWire(cursor.getString(0)!!) ?: continue
                    val itemId = cursor.getString(1)!!
                    val episodeId = cursor.getString(2)!!
                    val timestampMs = cursor.getLong(3)!!
                    val excerpt = cursor.getString(4)!!
                    val episodeTitle = cursor.getString(5)!!
                    val podcastId = cursor.getString(6)!!
                    val podcastTitle = cursor.getString(7)!!
                    val artworkUrl = cursor.getString(8)!!
                    rows += when (typed) {
                        LibrarySearchKind.Bookmark -> LibrarySearchResult.BookmarkMatch(
                            bookmarkId = itemId,
                            timestampMs = timestampMs,
                            episodeId = episodeId,
                            episodeTitle = episodeTitle,
                            podcastId = podcastId,
                            podcastTitle = podcastTitle,
                            artworkUrl = artworkUrl,
                            excerpt = excerpt,
                        )
                        LibrarySearchKind.Summary -> LibrarySearchResult.SummaryMatch(
                            episodeId = episodeId,
                            episodeTitle = episodeTitle,
                            podcastId = podcastId,
                            podcastTitle = podcastTitle,
                            artworkUrl = artworkUrl,
                            excerpt = excerpt,
                        )
                        LibrarySearchKind.Transcript -> LibrarySearchResult.TranscriptMatch(
                            episodeId = episodeId,
                            episodeTitle = episodeTitle,
                            podcastId = podcastId,
                            podcastTitle = podcastTitle,
                            artworkUrl = artworkUrl,
                            excerpt = excerpt,
                        )
                    }
                }
                QueryResult.Value(rows.toList())
            },
            parameters = parameterCount,
            binders = {
                bindString(0, expression)
                if (kind != null) bindString(1, kind.wire)
            },
        ).value
        return rows
    }

    private companion object {
        private const val SQL_SEARCH = """
            SELECT fts.kind, fts.itemId, fts.episodeId, fts.timestampMs,
                   snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
                   e.title AS episodeTitle, p.id AS podcastId,
                   p.title AS podcastTitle, p.artworkUrl AS artworkUrl
            FROM LibrarySearchIndex fts
            INNER JOIN Episode e ON e.id = fts.episodeId
            INNER JOIN Podcast p ON p.id = e.podcastId
            WHERE LibrarySearchIndex MATCH ?
            ORDER BY rank
            LIMIT 100
        """

        private const val SQL_SEARCH_BY_KIND = """
            SELECT fts.kind, fts.itemId, fts.episodeId, fts.timestampMs,
                   snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
                   e.title AS episodeTitle, p.id AS podcastId,
                   p.title AS podcastTitle, p.artworkUrl AS artworkUrl
            FROM LibrarySearchIndex fts
            INNER JOIN Episode e ON e.id = fts.episodeId
            INNER JOIN Podcast p ON p.id = e.podcastId
            WHERE LibrarySearchIndex MATCH ?
              AND fts.kind = ?
            ORDER BY rank
            LIMIT 100
        """
    }
}
```

> Note: the exact `db.driver.executeQuery` signature in SQLDelight 2.0.2 is:
> ```kotlin
> fun <R> executeQuery(
>     identifier: Int?,
>     sql: String,
>     mapper: (SqlCursor) -> QueryResult<R>,
>     parameters: Int,
>     binders: (SqlPreparedStatement.() -> Unit)? = null,
> ): QueryResult<R>
> ```
> If the implementer hits a signature mismatch, consult `app.cash.sqldelight.db.SqlDriver` directly. The mapper above uses `cursor.next().value` (Boolean unwrap from `QueryResult.Value`) and `cursor.getString(idx)` / `cursor.getLong(idx)` — the standard SQLDelight cursor API.

- [ ] **Step 3: Run the tests until they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.search.LibrarySearchRepositoryTest"`
Expected: PASS (7 tests).

- [ ] **Step 4: Test audit**

Dispatch the `test-quality-auditor` subagent on `LibrarySearchRepositoryTest.kt`. Address any critical / high issues. Re-run tests until clean.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/search/LibrarySearchRepository.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/search/LibrarySearchRepositoryTest.kt
git commit -m "slice2(search): LibrarySearchRepository over FTS5 index + in-memory tests"
```

---

## Task 7: Wire transcript-cache write into AiSummaryRepository

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt` (around lines 405–465 — the `runTranscript` method already in place).
- Modify: `composeApp/src/androidUnitTest/kotlin/app/kofipod/ai/AiSummaryRepositoryTest.kt` — add ONE new `@Test` method that piggy-backs on the existing fakes (`StubTranscriptFetcher`, `StubSummariser`) and the `build(...) → (repo, db)` helper already at the top of that file (verified at line 213, the existing `generate_persistsSummary_onTranscriptHappyPath` is the template). Adding a new test file would force us to duplicate ~200 lines of fakes/helpers; reuse is cheaper and more honest.

The hook in `AiSummaryRepository` is one block of code: after `summariser.generateFromText(...)` succeeds, persist the transcript text via `db.transcriptCacheQueries.upsert(...)`. We do NOT skip on duplicate fetch — `INSERT OR REPLACE` is idempotent. Inject `clock` is already a constructor dep, and `db: KofipodDatabase` exposes `transcriptCacheQueries` directly after the migration lands — no new ctor params needed.

- [ ] **Step 1: Write the failing test**

Add this `@Test` method to `composeApp/src/androidUnitTest/kotlin/app/kofipod/ai/AiSummaryRepositoryTest.kt`, placed directly after `generate_persistsSummary_onTranscriptHappyPath` (around line 238):

```kotlin
@Test
fun generate_persistsTranscriptText_intoTranscriptCache_andLightsUpFtsIndex() =
    runTest {
        val transcriptBody = "WEBVTT\n\n00:00.000 --> 00:02.000\nThe word kofipodbananaword appears here exactly once."
        val (repo, db) =
            build(
                initialKey = "k",
                transcripts = StubTranscriptFetcher.success(transcriptBody),
                summariser = StubSummariser(returns = StubSummariser.summary("Summary body.")),
            )
        insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

        repo.generate("ep1")
        advanceUntilIdle()

        // 1) Transcript text persisted, keyed by episodeId, with the body verbatim.
        val cached = db.transcriptCacheQueries.selectByEpisode("ep1").executeAsOneOrNull()
        assertNotNull(cached, "runTranscript must persist the fetched body for FTS indexing")
        assertEquals(transcriptBody, cached.text)
        assertTrue(cached.fetchedAtMs > 0, "fetchedAtMs must be set from the injected Clock")

        // 2) FTS trigger fired — the transcript-side index row exists with kind='transcript'.
        // Goes through LibrarySearchRepository because SQLDelight 2.0.2 doesn't expose
        // typed FTS queries (see LibrarySearchIndex.sq's documented fallback). This test
        // owns one production seam — that the AI repo writes to TranscriptCache so the
        // trigger fires — and the cleanest assertion path lives via the repo.
        val searchRepo = LibrarySearchRepository(db)
        val hits = searchRepo.search("kofipodbananaword").first()
        assertEquals(1, hits.size, "FTS row should be visible immediately via the AFTER INSERT trigger")
        val hit = hits.single()
        assertTrue(hit is LibrarySearchResult.TranscriptMatch)
        assertEquals("ep1", hit.episodeId)
    }
```

Required new imports at the top of the file (only add the ones not already present):

```kotlin
import app.kofipod.search.LibrarySearchRepository
import app.kofipod.search.LibrarySearchResult
import kotlinx.coroutines.flow.first
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
```

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.ai.AiSummaryRepositoryTest.generate_persistsTranscriptText_intoTranscriptCache_andLightsUpFtsIndex"`
Expected: **FAIL** with `cached == null` (assertion message: "runTranscript must persist the fetched body for FTS indexing"). Confirms the production path doesn't yet write the cache.

- [ ] **Step 2: Persist after a successful transcript fetch**

In `AiSummaryRepository.runTranscript`, immediately after the existing `setStage(episodeId, GenerationStage.Formatting, sizeBytes = null)` line at ~line 442, and BEFORE the `if (aiConfig.currentKey().isNullOrBlank())` defence-in-depth block at ~line 444, insert:

```kotlin
db.transcriptCacheQueries.upsert(
    episodeId = episodeId,
    text = transcriptText,
    fetchedAtMs = clock.now().toEpochMilliseconds(),
)
```

Why before the disconnect guard, not after: if the user disconnects mid-pipeline, we still want the transcript text on disk for future Library search — there's no key-bound secret in the transcript body, and the user may reconnect later and run summary again. The summary-upsert guard stays where it is because the *summary* is generated under a key that may be revoked.

- [ ] **Step 3: Run the test until it passes**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.ai.AiSummaryRepositoryTest.generate_persistsTranscriptText_intoTranscriptCache_andLightsUpFtsIndex"`
Expected: PASS.

- [ ] **Step 4: Test audit**

Dispatch the `test-quality-auditor` subagent on the modified `AiSummaryRepositoryTest.kt` (focus the prompt on the new `@Test` method only). Address any critical / high issues. Re-run tests until clean.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/ai/AiSummaryRepositoryTest.kt
git commit -m "slice2(search): persist fetched transcript text into TranscriptCache for FTS"
```

---

## Task 8: LibrarySearchViewModel + LibrarySearchScreen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchRow.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — add `viewModel { LibrarySearchViewModel(get()) }`.

- [ ] **Step 1: ViewModel with debounced query**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchViewModel.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.search.LibrarySearchKind
import app.kofipod.search.LibrarySearchRepository
import app.kofipod.search.LibrarySearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class LibrarySearchUiState(
    val query: String = "",
    val activeKind: LibrarySearchKind? = null,
    val results: List<LibrarySearchResult> = emptyList(),
)

class LibrarySearchViewModel(
    private val repo: LibrarySearchRepository,
) : ViewModel() {
    private val rawQuery = MutableStateFlow("")
    private val activeKind = MutableStateFlow<LibrarySearchKind?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LibrarySearchUiState> =
        combine(
            rawQuery.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
            activeKind,
        ) { q, k -> q to k }
            .flatMapLatest { (q, k) ->
                combine(
                    repo.search(q, k),
                    rawQuery,
                    activeKind,
                ) { results, currentQuery, currentKind ->
                    LibrarySearchUiState(currentQuery, currentKind, results)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySearchUiState())

    fun onQueryChanged(value: String) {
        rawQuery.value = value
    }

    fun onKindChipTapped(kind: LibrarySearchKind?) {
        activeKind.value = kind
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 200L
    }
}
```

- [ ] **Step 2: Screen + row composable**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchScreen.kt` — full-screen layout:
- Top: a TextField hosting the live query (auto-focused on entry), a "Cancel" / back affordance, and a row of FilterChip-style "All / Bookmarks / Summaries / Transcripts" toggles wired to `onKindChipTapped`.
- Body: a `LazyColumn` of `LibrarySearchRow`. Empty-query state shows a centered "Search bookmarks, summaries, transcripts" placeholder. Empty-results-with-query shows "No matches for \"$query\"".

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/LibrarySearchRow.kt` — a single result row:
- Square podcast artwork (use the existing `KPArtwork` primitive — same one Bookmarks list uses).
- Two-line subtitle: episode title (1 line, ellipsised) + podcast title (1 line, muted).
- Excerpt rendered with `<<…>>` markers replaced by **bold** spans (use `buildAnnotatedString`).
- Kind chip on the right (compact, 1-letter colour-coded — `B` / `S` / `T`).
- Click handler: BookmarkMatch → seek-to-timestamp via existing `Route.EpisodeDetail`-then-Player flow (same as Slice 1's bookmark-row tap); SummaryMatch / TranscriptMatch → navigate to `Route.EpisodeDetail(episodeId)` (no seek, since these are episode-level hits).

> **Implementer:** consult `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt` for the row visual treatment pattern (artwork + subtitles + tap behaviour). Reuse the same `KPArtwork`, `Spacer` rhythm, and tap target sizing.

- [ ] **Step 3: Wire the Koin factory**

In `CommonModule.kt`, add (next to other Pro `viewModel` bindings):

```kotlin
viewModel { app.kofipod.ui.screens.search.LibrarySearchViewModel(get()) }
```

And register the repo as a singleton next to `BookmarkRepository`:

```kotlin
single { app.kofipod.search.LibrarySearchRepository(db = get()) }
```

- [ ] **Step 4: Verify all flavors compile + iOS green**

Run: `./gradlew :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice2(search): LibrarySearchViewModel + Screen + Row"
```

---

## Task 9: Route + NavHost wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt:33` — add `Route.LibrarySearch` next to `Route.Bookmarks`.
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` — wire `composable<Route.LibrarySearch>`.

- [ ] **Step 1: Add the route**

In `Routes.kt`:

```kotlin
@Serializable data object LibrarySearch : Route
```

Place it next to `data object Bookmarks` for kind alignment.

- [ ] **Step 2: Wire the NavHost binding**

In `KofipodNavHost.kt`, add (next to the existing `composable<Route.Bookmarks>` block):

```kotlin
composable<Route.LibrarySearch> {
    val vm: LibrarySearchViewModel = koinViewModel()
    LibrarySearchScreen(
        viewModel = vm,
        onBack = { navController.popBackStack() },
        onOpenEpisode = { episodeId -> navController.navigate(Route.EpisodeDetail(episodeId)) },
        onSeekToBookmark = { episodeId, timestampMs ->
            // Mirror the existing Bookmarks list seek-or-play wiring. The
            // seek itself happens inside EpisodeDetail / Player; this nav
            // step just lands the user on the detail screen with the
            // playback pointer set. If a player-seek deep link exists, use
            // it; otherwise route to Player and rely on PlaybackState being
            // updated separately.
            navController.navigate(Route.EpisodeDetail(episodeId))
        },
    )
}
```

> **Implementer:** check how `BookmarksScreen` already wires its tap-to-seek path (Slice 1 commit `8669660`). If a shared helper exists for "seek on episode at ms", reuse it instead of duplicating the call shape. The exact navigation graph is out of plan scope — match what Slice 1 settled on.

- [ ] **Step 3: Verify compile**

Run: `./gradlew :composeApp:compileFossDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt
git commit -m "slice2(search): Route.LibrarySearch + NavHost binding"
```

---

## Task 10: Library entry-point + Pro-gate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` — add `onLibrarySearchTapped(): Boolean`.
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt` — add a search-bar entry-point row above the existing Bookmarks row, wire its tap to `onLibrarySearchTapped()` then `onOpenLibrarySearch()`.

- [ ] **Step 1: Mirror the Bookmarks gate in the Library VM**

In `LibraryViewModel.kt`, add directly below `onBookmarksTapped`:

```kotlin
/**
 * Returns true when the caller should navigate to the Library search screen.
 * Returns false (and opens the paywall) when the user is Free or Unknown.
 * Same gate semantics as [onBookmarksTapped].
 */
fun onLibrarySearchTapped(): Boolean =
    when (pro.state.value) {
        is ProEntitlement.Pro -> true
        ProEntitlement.Free,
        ProEntitlement.Unknown,
        -> {
            paywallRouter.requestPaywall("paywall_library_search")
            false
        }
    }
```

No constructor change — the deps (`pro`, `paywallRouter`) already exist.

- [ ] **Step 2: Add the search-bar row in LibraryScreen**

`LibraryScreen.kt` already takes `onOpenBookmarks: () -> Unit` (line 76). Add a sibling parameter:

```kotlin
onOpenLibrarySearch: () -> Unit,
```

Render the row directly above the Bookmarks row (the existing block around line 248–271). Match the existing Bookmarks row's height, icon-leading layout, and tap target size; the row's label reads "Search library" and its leading icon is `KPIconName.Search` (or whichever existing icon name matches the global Search affordance — inspect `KPIconName.kt` and pick the closest).

```kotlin
Row(
    Modifier
        .fillMaxWidth()
        .clickable {
            if (viewModel.onLibrarySearchTapped()) onOpenLibrarySearch()
        }
        .padding(/* match the existing Bookmarks row's padding */),
    verticalAlignment = Alignment.CenterVertically,
) {
    KPIcon(name = KPIconName.Search, color = c.text, size = 20.dp)
    Spacer(Modifier.size(/* same as Bookmarks row */))
    Text("Search library", color = c.text, /* same typography as Bookmarks row */)
}
```

> **Implementer:** copy the Bookmarks row's existing visual treatment exactly — do not redesign. The point of this slice is the search backend, not a Library refresh. If `KPIconName.Search` doesn't exist, use `KPIconName.More` as a placeholder and leave a `// TODO(slice2-icon)` so the design pass can swap in the right glyph later.

- [ ] **Step 3: Wire the new callback at the AppShell level**

Wherever `LibraryScreen` is currently constructed inside `AppShell` / `KofipodNavHost`, plumb `onOpenLibrarySearch = { navController.navigate(Route.LibrarySearch) }` next to the existing `onOpenBookmarks` argument.

- [ ] **Step 4: Verify all green-checks**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testFossDebugUnitTest :composeApp:testPlayDebugUnitTest`
Expected: BUILD SUCCESSFUL with all tests green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/ \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt
git commit -m "slice2(search): Library entry-point + Pro-gated search-bar tap"
```

---

## Task 11: Slice-end review + emulator verify

- [ ] **Step 1: Final code review by sub-agent**

Per the standing rule in `~/.claude/CLAUDE.md` ("ALWAYS get your code reviewed by a sub agent with code review skills"), dispatch `feature-dev:code-reviewer` over the full Slice 2 diff (`git diff <slice-1-tip>..HEAD`). Address every critical / high finding before declaring done.

- [ ] **Step 2: Final test audit**

Dispatch `test-quality-auditor` over the three new test files together. Address every critical / high finding.

- [ ] **Step 3: Build a debug FOSS APK + install on Pixel_9a**

```bash
./gradlew :composeApp:assembleFossDebug
~/Library/Android/sdk/platform-tools/adb install -r composeApp/build/outputs/apk/foss/debug/kofipod-foss-*-debug.apk
```

- [ ] **Step 4: Manual emulator verification**

Goldens to verify (capture `uiautomator dump` between steps if positional taps are needed):
1. Launch app → Library → tap "Search library" row → search screen opens (FOSS flavor grants Pro unconditionally; Paywall sheet does NOT appear).
2. Subscribe to a podcast (use AI + a16z or whichever is already in the test data).
3. Add a Bookmark via the Player on one episode (Slice 1 path) with note "continual learning experiment".
4. Run AI Summary on the same episode (or a different one with a publisher transcript) so a `EpisodeAiSummary` row + `TranscriptCache` row land.
5. Open Library → Search library → type "continual" — expect a BookmarkMatch row, a SummaryMatch row, and (if the transcript covered that word) a TranscriptMatch row.
6. Tap a BookmarkMatch row → expect navigation to the episode at the bookmark's timestamp.
7. Tap a kind chip ("Bookmarks") → expect filtered results.
8. Long-press delete the bookmark from the per-episode Saved section → return to Library search → expect the BookmarkMatch row to disappear (trigger sync).
9. Database introspection sanity-check (optional, useful if a test golden fails):
   ```bash
   ~/Library/Android/sdk/platform-tools/adb shell run-as app.kofipod.foss.debug \
     sqlite3 databases/kofipod.db "SELECT kind, episodeId, substr(text, 1, 40) FROM LibrarySearchIndex LIMIT 10;"
   ```

If anything in step 5 returns zero rows when it should match, the most likely culprit is the migration backfill missing pre-existing rows — check the `INSERT INTO LibrarySearchIndex SELECT ... FROM Bookmark / EpisodeAiSummary` blocks in `17.sqm` ran successfully (look at `sqlite_master` for the FTS table existence + a `SELECT count(*) FROM LibrarySearchIndex`).

- [ ] **Step 5: Slice-1-style commit log review**

Confirm the slice's commit titles all start with `slice2(search):` for greppability — same convention Slice 1 used (`slice1(bookmarks):`).

- [ ] **Step 6: Update the slice execution memory**

Save a one-line update to the existing memory file `project_kofipod.md` (Kofipod project — slice execution state) noting that Slice 2 is complete and the worktree branch is one slice ahead of master.

---

## Self-review checklist

- **Spec coverage:** F4 (Transcript & summary search) is fully covered: search bar on Library, FTS5 over transcript / summary / bookmark, four result types (snippet deferred to Slice 3), tap → source at timestamp. ✅
- **Pro-gating:** the search-bar entry-point uses the same `onLibrarySearchTapped(): Boolean` shape as Slice 1's `onBookmarksTapped()`, with a new trigger key `paywall_library_search`. The Paywall sheet itself was implemented in Slice 0 and needs no edit. ✅
- **Schema bump:** one migration file (17.sqm), one constant bump (`DB_SCHEMA_VERSION = 17`), no edits to existing tables. ✅
- **Backfill:** existing Bookmark + EpisodeAiSummary rows are seeded into the FTS index inside the migration transaction so users who upgrade with existing saves get instant search. ✅
- **Transcript caching:** new `TranscriptCache` table; `AiSummaryRepository.runTranscript` writes through. The hook lands BEFORE the disconnect-during-pipeline guard because transcript bodies aren't key-bound secrets. ✅
- **iOS green:** every new file lives in `commonMain` with no `java.*` / `androidx.*` imports. SQLDelight FTS5 / triggers compile across all three drivers (Android / iOS / JDBC). ✅
- **Detekt forbidden imports:** no new androidx-only imports in commonMain. ✅
- **Test discipline:** three test files cover sanitiser, repository round-trip + cascade behaviour + kind filter, and the AI repo's transcript-cache hook. Each test gets audited. ✅
- **Snippets explicitly out of scope:** the `kind='snippet'` enum value is intentionally absent — adding it before the table exists would create a useless code path. Slice 3 owns it. ✅
- **No design drift on free surfaces:** the only Library change is one new row that mirrors the existing Bookmarks row exactly. Per spec ("existing free-tier UI drift is intentionally out-of-scope"), no other Library composables are touched. ✅

---

## Open follow-ups (defer to later slices)

- **Backfill from existing publisher transcripts not yet fetched.** This slice only persists transcripts the user has run AI Summary on. Mass pre-fetching every subscribed episode's transcript is out of scope (battery + bandwidth + courtesy to publishers).
- **Snippet indexing.** Slice 3 adds `Snippet.sq` + the matching FTS triggers in `18.sqm`.
- **Search history / recent queries.** Not in spec; if user research surfaces it, a dedicated slice.
- **Per-podcast scope.** Spec mentions filtering by podcast for Bookmarks; the search screen could grow the same chip later.
