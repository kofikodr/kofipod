# Kofipod Pro — Slice 1 (Bookmarks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Slice-0 toy bookmark gate with a real Pro feature — tap the Player's bookmark icon to capture a timestamp (with optional one-line note), browse all bookmarks per-episode and globally, and seek-or-play from a row tap.

**Architecture:** A new `Bookmark` SQLDelight table (FK-cascaded to `Episode` and `Podcast`), a `BookmarkRepository` with `Flow`-based reads and a single-shot `add` method, a quick-add bottom sheet hoisted at `AppShell` (so the Player can dismiss into a transient note editor without losing playback context), a per-episode "Saved" section on `EpisodeDetailScreen` (NOT a fifth tab — see CLAUDE.md "Tab strip stays four max"), and a global `BookmarksScreen` reachable from a Library entry-point row. Reuses the existing `PaywallRouter` gating pattern: `Pro` users go through the real path; `Free` / `Unknown` users are routed to the Paywall sheet by the same `onBookmarkTapped` entry that already exists in `PlayerViewModel`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight (schema 16), Koin DI, Material 3 ModalBottomSheet, kotlinx.datetime, kotlinx.coroutines Flow.

---

## File structure

### New files

- `composeApp/src/commonMain/sqldelight/app/kofipod/db/Bookmark.sq` — table + queries.
- `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/16.sqm` — schema bump.
- `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt` — DAO + Flow projections.
- `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkComposer.kt` — `MutableStateFlow<BookmarkComposerState>` + `requestQuickAdd(...)` / `cancel()` / `confirm(...)` API. Hoisted at AppShell.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt` — global list screen.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt` — flat list, optional podcast filter, search.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarkComposerSheet.kt` — Material 3 ModalBottomSheet for the quick-add note.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt` — per-episode Saved rows for the detail screen.
- `composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt` — unit tests over `inMemoryDatabase()`.
- `composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/player/PlayerViewModelBookmarkTest.kt` — VM-level test that the Pro path now invokes the composer instead of emitting the toy snackbar.

### Modified files

- `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt` — add `Route.Bookmarks`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` — wire `composable<Route.Bookmarks>`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt` — `onBookmarkTapped` Pro branch now calls `BookmarkComposer.requestQuickAdd(...)` instead of the toy snackbar.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt` — hoist `BookmarkComposerSheet`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt` — add a "Bookmarks" entry-point row (Pro-gated tap).
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` — `onBookmarksTapped()` mirrors PlayerViewModel's gate logic.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` — render `SavedSection` below the tab strip (independent of the four-tab strip).
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt` — expose a `bookmarksFlow(episodeId)` projection and a `seekToBookmark(timestampMs)` (delegates to the existing `seekToChapter` semantics).
- `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — bind `BookmarkRepository`, `BookmarkComposer`, and the new `BookmarksViewModel`.
- `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupController.kt` (only the `DB_SCHEMA_VERSION` constant if it lives there — see Task 2 for the actual location).

### Touched but NOT modifying behaviour
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt` — no edit; the existing `viewModel::onBookmarkTapped` wiring keeps working.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt` — no edit; the bookmark icon and Pro-gated callback already exist.

---

## Conventions worth re-reading before starting

- `composeApp/CLAUDE.md` — "iOS compile must stay green" (no `java.*`, no `androidx.*` in `commonMain`), "Tab strip stays four max", `viewModel { ... }` factory parity rule.
- The existing Pro-gate pattern in `PlayerViewModel.onBookmarkTapped` (lines 183–190) is the contract; preserve the Free/Unknown → `paywallRouter.requestPaywall("paywall_bookmark")` branch verbatim.
- Migrations: one `.sqm` file per schema bump; do **not** edit existing tables. Current schema version is **15**, so this slice ends at **16**.
- Tests: per CLAUDE.md, "all tests must pass before declaring work done." Run the green-check sequence at the end of each task: `ktlintFormat`, `detekt`, `compilePlayDebugKotlinAndroid`, `compileFossDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`, `testPlayDebugUnitTest`, `testFossDebugUnitTest`. Slice-end emulator verify uses `Pixel_9a`.

---

## Task 1: Domain type for a Bookmark row

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/Bookmark.kt`

- [ ] **Step 1: Create the domain type**

`composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/Bookmark.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

data class Bookmark(
    val id: String,
    val episodeId: String,
    val podcastId: String,
    val timestampMs: Long,
    val note: String?,
    val createdAtMs: Long,
)

data class BookmarkWithContext(
    val bookmark: Bookmark,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String,
)
```

- [ ] **Step 2: Verify ktlint + detekt are green**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/Bookmark.kt
git commit -m "slice1(bookmarks): add Bookmark + BookmarkWithContext domain types"
```

---

## Task 2: SQLDelight schema + migration

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Bookmark.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/16.sqm`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupConstants.kt` (or wherever `DB_SCHEMA_VERSION` lives — locate via `grep -rn "DB_SCHEMA_VERSION =" composeApp/src/commonMain` and bump the constant from 15 to 16).

- [ ] **Step 1: Create the schema file**

`composeApp/src/commonMain/sqldelight/app/kofipod/db/Bookmark.sq`:

```sql
-- Slice 1 (Pro): per-timestamp bookmark on an episode, with optional one-line note.
-- Independent of Snippets — a Bookmark stores a moment, no audio.
--
-- Cascades from both Episode and Podcast. The episodeId FK on its own is enough
-- (Episode cascades from Podcast already), but the redundant podcastId column is
-- materialised so the global Bookmarks list can render podcast title / artwork
-- without a JOIN through Episode for every row.
CREATE TABLE Bookmark (
    id           TEXT NOT NULL PRIMARY KEY,
    episodeId    TEXT NOT NULL,
    podcastId    TEXT NOT NULL,
    timestampMs  INTEGER NOT NULL,
    note         TEXT,
    createdAtMs  INTEGER NOT NULL,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE,
    FOREIGN KEY (podcastId) REFERENCES Podcast(id) ON DELETE CASCADE
);

CREATE INDEX Bookmark_byEpisode ON Bookmark(episodeId, timestampMs);
CREATE INDEX Bookmark_byPodcast ON Bookmark(podcastId, createdAtMs);
CREATE INDEX Bookmark_byCreated ON Bookmark(createdAtMs);

insert:
INSERT INTO Bookmark (id, episodeId, podcastId, timestampMs, note, createdAtMs)
VALUES (?, ?, ?, ?, ?, ?);

updateNote:
UPDATE Bookmark SET note = ? WHERE id = ?;

deleteById:
DELETE FROM Bookmark WHERE id = ?;

selectByEpisode:
SELECT * FROM Bookmark WHERE episodeId = ? ORDER BY timestampMs ASC;

countByEpisode:
SELECT COUNT(*) AS c FROM Bookmark WHERE episodeId = ?;

-- Global list, newest-first. JOIN materialises podcast/episode metadata
-- so the UI doesn't fan out to per-row look-ups.
selectAllWithContext:
SELECT
    b.id           AS id,
    b.episodeId    AS episodeId,
    b.podcastId    AS podcastId,
    b.timestampMs  AS timestampMs,
    b.note         AS note,
    b.createdAtMs  AS createdAtMs,
    e.title        AS episodeTitle,
    p.title        AS podcastTitle,
    p.artworkUrl   AS artworkUrl
FROM Bookmark b
INNER JOIN Episode e ON e.id = b.episodeId
INNER JOIN Podcast p ON p.id = b.podcastId
ORDER BY b.createdAtMs DESC;

selectByPodcastWithContext:
SELECT
    b.id           AS id,
    b.episodeId    AS episodeId,
    b.podcastId    AS podcastId,
    b.timestampMs  AS timestampMs,
    b.note         AS note,
    b.createdAtMs  AS createdAtMs,
    e.title        AS episodeTitle,
    p.title        AS podcastTitle,
    p.artworkUrl   AS artworkUrl
FROM Bookmark b
INNER JOIN Episode e ON e.id = b.episodeId
INNER JOIN Podcast p ON p.id = b.podcastId
WHERE b.podcastId = ?
ORDER BY b.createdAtMs DESC;
```

- [ ] **Step 2: Create the migration file**

`composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/16.sqm`:

```sql
-- Slice 1 (Pro Bookmarks): introduce the Bookmark table.
-- Brand-new table — no live data to migrate. Indexes are part of the same
-- transaction so a partial migration can't leave the table un-indexed.

CREATE TABLE Bookmark (
    id           TEXT NOT NULL PRIMARY KEY,
    episodeId    TEXT NOT NULL,
    podcastId    TEXT NOT NULL,
    timestampMs  INTEGER NOT NULL,
    note         TEXT,
    createdAtMs  INTEGER NOT NULL,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE,
    FOREIGN KEY (podcastId) REFERENCES Podcast(id) ON DELETE CASCADE
);

CREATE INDEX Bookmark_byEpisode ON Bookmark(episodeId, timestampMs);
CREATE INDEX Bookmark_byPodcast ON Bookmark(podcastId, createdAtMs);
CREATE INDEX Bookmark_byCreated ON Bookmark(createdAtMs);
```

- [ ] **Step 3: Bump DB_SCHEMA_VERSION**

Run: `grep -rn "DB_SCHEMA_VERSION" composeApp/src/commonMain --include="*.kt"`
Expected output names exactly one `const val DB_SCHEMA_VERSION = 15` declaration. Edit that file, change `15` to `16`. (If grep returns zero hits, the constant lives in the SQLDelight-generated `Schema` object and no Kotlin edit is needed; in that case skip this step.)

- [ ] **Step 4: Compile-only smoke check**

Run: `./gradlew :composeApp:compilePlayDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. SQLDelight generation runs as part of compilation; failure here means the schema or migration has a syntax error.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/Bookmark.sq \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/16.sqm \
        composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupConstants.kt
git commit -m "slice1(bookmarks): SQLDelight schema 16 — add Bookmark table"
```

---

## Task 3: BookmarkRepository — write side, with failing test

**Files:**
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt`

- [ ] **Step 1: Write the failing test for `add`**

`composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import app.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookmarkRepositoryTest {
    private fun seedEpisode(db: app.kofipod.db.KofipodDatabase, podcastId: String = "pod-1", episodeId: String = "ep-1") {
        db.podcastQueries.insert(
            id = podcastId,
            title = "Test Show",
            author = "",
            description = "",
            feedUrl = "",
            artworkUrl = "",
            categoryId = null,
            language = "",
            episodeCount = 0,
            primaryCategory = "",
            addedAt = 0,
        )
        db.episodeQueries.insert(
            id = episodeId,
            podcastId = podcastId,
            guid = episodeId,
            title = "Test Episode",
            description = "",
            publishedAt = 0,
            durationSec = 3600,
            enclosureUrl = "https://example.test/ep.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )
    }

    @Test
    fun add_writesRow_withProvidedFields() {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)

        val id = repo.add(
            episodeId = "ep-1",
            podcastId = "pod-1",
            timestampMs = 60_000L,
            note = "good moment",
            nowMs = 1_700_000_000_000L,
        )

        val rows = db.bookmarkQueries.selectByEpisode("ep-1").executeAsList()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(id, row.id)
        assertEquals("ep-1", row.episodeId)
        assertEquals("pod-1", row.podcastId)
        assertEquals(60_000L, row.timestampMs)
        assertEquals("good moment", row.note)
        assertEquals(1_700_000_000_000L, row.createdAtMs)
    }
}
```

(Confirm `podcastQueries.insert` parameter list against the live `Podcast.sq` before pasting; the field set above is the post-merge default. If a column was added since this plan was written, mirror it.)

- [ ] **Step 2: Run the test — should fail (class doesn't exist)**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.bookmarks.BookmarkRepositoryTest"`
Expected: FAIL. Compilation error: "Unresolved reference: BookmarkRepository".

- [ ] **Step 3: Implement BookmarkRepository's write side**

`composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * Owner of the Bookmark table.
 *
 * Reads expose Flows so the UI re-renders on every insert / delete without
 * needing to know which mutation happened. Writes are synchronous — bookmarks
 * are tiny (timestamp + optional note) and add() is a single INSERT.
 */
class BookmarkRepository(
    private val db: KofipodDatabase,
) {
    /**
     * Insert a new bookmark and return its generated id. Caller is responsible
     * for ensuring [episodeId] is in the library (the FK will reject otherwise).
     *
     * [nowMs] is injectable so unit tests can pin createdAtMs without faking a clock.
     */
    fun add(
        episodeId: String,
        podcastId: String,
        timestampMs: Long,
        note: String?,
        nowMs: Long,
    ): String {
        val id = generateId(nowMs)
        db.bookmarkQueries.insert(
            id = id,
            episodeId = episodeId,
            podcastId = podcastId,
            timestampMs = timestampMs,
            note = note?.takeIf { it.isNotBlank() },
            createdAtMs = nowMs,
        )
        return id
    }

    fun deleteById(id: String) {
        db.bookmarkQueries.deleteById(id)
    }

    fun updateNote(id: String, note: String?) {
        db.bookmarkQueries.updateNote(note?.takeIf { it.isNotBlank() }, id)
    }

    fun observeForEpisode(episodeId: String): Flow<List<Bookmark>> =
        db.bookmarkQueries
            .selectByEpisode(episodeId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map {
                    Bookmark(
                        id = it.id,
                        episodeId = it.episodeId,
                        podcastId = it.podcastId,
                        timestampMs = it.timestampMs,
                        note = it.note,
                        createdAtMs = it.createdAtMs,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    fun observeAll(): Flow<List<BookmarkWithContext>> =
        db.bookmarkQueries
            .selectAllWithContext()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    BookmarkWithContext(
                        bookmark = Bookmark(
                            id = row.id,
                            episodeId = row.episodeId,
                            podcastId = row.podcastId,
                            timestampMs = row.timestampMs,
                            note = row.note,
                            createdAtMs = row.createdAtMs,
                        ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    fun observeForPodcast(podcastId: String): Flow<List<BookmarkWithContext>> =
        db.bookmarkQueries
            .selectByPodcastWithContext(podcastId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    BookmarkWithContext(
                        bookmark = Bookmark(
                            id = row.id,
                            episodeId = row.episodeId,
                            podcastId = row.podcastId,
                            timestampMs = row.timestampMs,
                            note = row.note,
                            createdAtMs = row.createdAtMs,
                        ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun generateId(nowMs: Long): String {
        // Sortable-ish id: 13-char base36 timestamp + 8-char base36 entropy.
        // Collision-free for one-per-second-per-device adds, ample for a tap-to-bookmark surface.
        val rand = Random.nextLong(0L, Long.MAX_VALUE)
        return nowMs.toString(36).padStart(8, '0') + "-" + rand.toString(36).take(8)
    }
}
```

- [ ] **Step 4: Run the test — should pass**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.bookmarks.BookmarkRepositoryTest"`
Expected: BUILD SUCCESSFUL. 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt
git commit -m "slice1(bookmarks): BookmarkRepository.add + write-side test"
```

---

## Task 4: BookmarkRepository — read side test

**Files:**
- Modify: `composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt`

- [ ] **Step 1: Add a failing flow test for `observeForEpisode` ordering**

Append to `BookmarkRepositoryTest.kt`:

```kotlin
    @Test
    fun observeForEpisode_returnsRowsOrderedByTimestamp_andUpdatesOnInsert() = kotlinx.coroutines.test.runTest {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)

        val flow = repo.observeForEpisode("ep-1")

        // Insert two bookmarks in non-ascending timestamp order.
        repo.add("ep-1", "pod-1", timestampMs = 120_000L, note = null, nowMs = 100L)
        repo.add("ep-1", "pod-1", timestampMs = 30_000L, note = "early", nowMs = 200L)

        val rows = kotlinx.coroutines.flow.first(flow) { it.size == 2 }
        assertEquals(listOf(30_000L, 120_000L), rows.map { it.timestampMs })
        assertEquals("early", rows[0].note)
        assertNull(rows[1].note)
    }

    @Test
    fun deleteById_removesRow() = kotlinx.coroutines.test.runTest {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)
        val id = repo.add("ep-1", "pod-1", 60_000L, null, 100L)

        assertEquals(1, db.bookmarkQueries.countByEpisode("ep-1").executeAsOne().c)
        repo.deleteById(id)
        assertEquals(0, db.bookmarkQueries.countByEpisode("ep-1").executeAsOne().c)
    }
```

(If `kotlinx.coroutines.flow.first` with a predicate isn't statically resolvable as a top-level call, use `flow.first { it.size == 2 }` after an explicit `import kotlinx.coroutines.flow.first`. Adjust imports inline; the assertion logic is the contract.)

- [ ] **Step 2: Run the tests — should pass**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.bookmarks.BookmarkRepositoryTest"`
Expected: BUILD SUCCESSFUL. 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkRepositoryTest.kt
git commit -m "slice1(bookmarks): cover observeForEpisode ordering + deleteById"
```

---

## Task 5: BookmarkComposer — pending-add state seam

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkComposer.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkComposerTest.kt`

`BookmarkComposer` is a process-singleton that the Player VM pokes when the user taps the bookmark icon and which the AppShell-hosted `BookmarkComposerSheet` observes. Decoupling this from the Player VM keeps the sheet alive through navigation away from the Player.

- [ ] **Step 1: Write the failing test**

`composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkComposerTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run the test — should fail (class doesn't exist)**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.bookmarks.BookmarkComposerTest"`
Expected: FAIL with "Unresolved reference: BookmarkComposer".

- [ ] **Step 3: Implement BookmarkComposer**

`composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkComposer.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class BookmarkComposerState {
    data object Hidden : BookmarkComposerState()

    data class Visible(
        val episodeId: String,
        val podcastId: String,
        val episodeTitle: String,
        val podcastTitle: String,
        val timestampMs: Long,
    ) : BookmarkComposerState()
}

/**
 * Process-wide bus between "user tapped Bookmark on the Player" and the
 * AppShell-hosted [BookmarkComposerSheet]. Hoisting at the shell rather
 * than inside the Player screen lets the sheet survive navigation (e.g.
 * the user pulls it open and then taps the back button on the player —
 * the sheet stays up until they Save or Cancel).
 *
 * Single-instance via Koin. State is intentionally last-write-wins:
 * tapping bookmark again while a previous quick-add is still open
 * replaces the in-flight snapshot. That matches user intent ("oops,
 * actually grab THIS moment instead").
 */
class BookmarkComposer {
    private val _state = MutableStateFlow<BookmarkComposerState>(BookmarkComposerState.Hidden)
    val state: StateFlow<BookmarkComposerState> = _state.asStateFlow()

    fun requestQuickAdd(
        episodeId: String,
        podcastId: String,
        episodeTitle: String,
        podcastTitle: String,
        timestampMs: Long,
    ) {
        _state.value = BookmarkComposerState.Visible(
            episodeId = episodeId,
            podcastId = podcastId,
            episodeTitle = episodeTitle,
            podcastTitle = podcastTitle,
            timestampMs = timestampMs,
        )
    }

    fun cancel() {
        _state.value = BookmarkComposerState.Hidden
    }
}
```

- [ ] **Step 4: Run the tests — should pass**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.bookmarks.BookmarkComposerTest"`
Expected: BUILD SUCCESSFUL. 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkComposer.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/bookmarks/BookmarkComposerTest.kt
git commit -m "slice1(bookmarks): BookmarkComposer pending-add seam + tests"
```

---

## Task 6: Wire repo + composer into Koin

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

- [ ] **Step 1: Add the singletons**

Locate the line `single { PaywallRouter() }` (around `CommonModule.kt:260`). Immediately below it, add:

```kotlin
        single { app.kofipod.bookmarks.BookmarkRepository(db = get()) }
        single { app.kofipod.bookmarks.BookmarkComposer() }
```

- [ ] **Step 2: Compile-only check**

Run: `./gradlew :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice1(bookmarks): bind BookmarkRepository + BookmarkComposer in Koin"
```

---

## Task 7: PlayerViewModel — replace toy snackbar with composer poke

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/player/PlayerViewModelBookmarkTest.kt`

- [ ] **Step 1: Write the failing VM test**

`composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/player/PlayerViewModelBookmarkTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import app.kofipod.bookmarks.BookmarkComposer
import app.kofipod.bookmarks.BookmarkComposerState
import app.kofipod.playback.PlayerState
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.PaywallState
import app.kofipod.pro.ProEntitlement
import app.kofipod.testing.FakePlayerHarness
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the Slice 1 contract for [PlayerViewModel.onBookmarkTapped]:
 *   - Pro user → composer.requestQuickAdd(...) called with current player snapshot.
 *   - Free user → paywallRouter.requestPaywall("paywall_bookmark") called.
 *   - Unknown  → paywall path (matches Slice 0 contract: no Pro action while uncertain).
 */
class PlayerViewModelBookmarkTest {
    @Test
    fun onBookmarkTapped_whenPro_pokesComposerWithCurrentPlayerSnapshot() {
        val harness = FakePlayerHarness().apply {
            currentPlayerState = PlayerState(
                episodeId = "ep-7",
                podcastId = "pod-7",
                podcastTitle = "Show 7",
                title = "Episode 7",
                positionMs = 12_345L,
                isPlaying = true,
            )
            currentEntitlement = ProEntitlement.Pro(ProEntitlement.Source.Individual)
        }
        val composer = BookmarkComposer()
        val vm = harness.buildPlayerViewModel(composer)

        vm.onBookmarkTapped()

        val state = composer.state.value
        assertTrue(state is BookmarkComposerState.Visible)
        assertEquals("ep-7", state.episodeId)
        assertEquals(12_345L, state.timestampMs)
        assertEquals("Show 7", state.podcastTitle)
        // Paywall must NOT be opened when the user is already Pro.
        assertEquals(PaywallState.Hidden, harness.paywallRouter.state.value)
    }

    @Test
    fun onBookmarkTapped_whenFree_opensPaywall_andLeavesComposerHidden() {
        val harness = FakePlayerHarness().apply { currentEntitlement = ProEntitlement.Free }
        val composer = BookmarkComposer()
        val vm = harness.buildPlayerViewModel(composer)

        vm.onBookmarkTapped()

        assertEquals(BookmarkComposerState.Hidden, composer.state.value)
        val paywall = harness.paywallRouter.state.value
        assertTrue(paywall is PaywallState.Visible)
        assertEquals("paywall_bookmark", paywall.triggerKey)
    }

    @Test
    fun onBookmarkTapped_whenUnknown_opensPaywall() {
        val harness = FakePlayerHarness().apply { currentEntitlement = ProEntitlement.Unknown }
        val composer = BookmarkComposer()
        val vm = harness.buildPlayerViewModel(composer)

        vm.onBookmarkTapped()

        val paywall = harness.paywallRouter.state.value
        assertTrue(paywall is PaywallState.Visible)
    }
}
```

- [ ] **Step 2: Build a `FakePlayerHarness` test helper**

If `FakePlayerHarness` does not already exist, create:

`composeApp/src/androidUnitTest/kotlin/app/kofipod/testing/FakePlayerHarness.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.testing

import app.kofipod.bookmarks.BookmarkComposer
import app.kofipod.playback.PlayerState
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.ui.UiEventBus
import app.kofipod.ui.screens.player.PlayerViewModel

/**
 * Minimal-surface harness for unit-testing PlayerViewModel methods that don't need
 * a live player. Only methods touched by the current test should be wired.
 *
 * Adds dependencies as needed; resist the urge to wire every collaborator.
 */
class FakePlayerHarness {
    var currentPlayerState: PlayerState = PlayerState()
    var currentEntitlement: ProEntitlement = ProEntitlement.Free

    val paywallRouter = PaywallRouter()

    fun buildPlayerViewModel(composer: BookmarkComposer): PlayerViewModel {
        // Subagent: locate the post-merge PlayerViewModel constructor and stub
        // every dep with the lightest no-op fake that satisfies type-checking.
        // KofipodPlayer / PlaybackRepository / EpisodeSource / SettingsRepository
        // / Sharer / DownloadRepository each have an interface or have-a-noop
        // shape already used in this codebase — reuse those, do not introduce a
        // fresh fake hierarchy. The harness exists ONLY to call onBookmarkTapped.
        TODO("Fill in deps from the live PlayerViewModel constructor")
    }
}
```

The `TODO` is intentional — the implementer fills it after reading the live constructor. The behavioural contract under test (composer poke vs paywall request) does not depend on player / playback / settings, so cheap no-op fakes are sufficient.

- [ ] **Step 3: Run the test — should fail**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.ui.screens.player.PlayerViewModelBookmarkTest"`
Expected: FAIL — either `TODO` throws, or current PlayerViewModel still emits the toy snackbar so the composer state is still `Hidden`.

- [ ] **Step 4: Modify PlayerViewModel to take BookmarkComposer**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt`:

Add to the import block:
```kotlin
import app.kofipod.bookmarks.BookmarkComposer
```

Append `private val bookmarks: BookmarkComposer,` to the constructor (after `bus`):

```kotlin
class PlayerViewModel(
    private val player: KofipodPlayer,
    private val playback: PlaybackRepository,
    private val episodes: EpisodeSource,
    private val settings: SettingsRepository,
    private val sharer: Sharer,
    private val downloads: DownloadRepository,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val bus: UiEventBus,
    private val bookmarks: BookmarkComposer,
) : ViewModel() {
```

Replace the existing `onBookmarkTapped` body (lines 183–190) with:

```kotlin
    /**
     * Pro users open a quick-add composer pre-filled with the current player
     * position. Free / Unknown users hit the Paywall (Slice 0 contract).
     */
    fun onBookmarkTapped() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> {
                val p = state.value.player
                val episodeId = p.episodeId ?: return
                if (p.podcastId.isBlank()) return
                bookmarks.requestQuickAdd(
                    episodeId = episodeId,
                    podcastId = p.podcastId,
                    episodeTitle = p.title,
                    podcastTitle = p.podcastTitle,
                    timestampMs = p.positionMs,
                )
            }
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_bookmark")
        }
    }
```

Drop the now-orphan `import app.kofipod.ui.UiEvent` line if no other code uses it (search the file with `grep -n "UiEvent\b"`).

- [ ] **Step 5: Update Koin factory to pass `BookmarkComposer`**

In `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`, locate the `viewModel { PlayerViewModel(...) }` block (around `CommonModule.kt:361`) and add `bookmarks = get(),` to the argument list:

```kotlin
        viewModel {
            PlayerViewModel(
                player = get(),
                playback = get(),
                episodes = get<EpisodeSource>(),
                settings = get(),
                sharer = get(),
                downloads = get(),
                pro = get(),
                paywallRouter = get(),
                bus = get(),
                bookmarks = get(),
            )
        }
```

- [ ] **Step 6: Run all tests — should pass**

Run: `./gradlew :composeApp:testPlayDebugUnitTest --tests "app.kofipod.ui.screens.player.PlayerViewModelBookmarkTest"`
Expected: BUILD SUCCESSFUL. 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/player/PlayerViewModelBookmarkTest.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/testing/FakePlayerHarness.kt
git commit -m "slice1(bookmarks): wire onBookmarkTapped to BookmarkComposer (Pro path)"
```

---

## Task 8: BookmarkComposerSheet UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarkComposerSheet.kt`

The sheet shows the snapshot (podcast title, episode title, formatted timestamp), an optional one-line note text field, and Save / Cancel actions. Save inserts via `BookmarkRepository.add(...)` and dismisses; Cancel dismisses without writing.

- [ ] **Step 1: Implement the sheet**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarkComposerSheet.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.BookmarkComposer
import app.kofipod.bookmarks.BookmarkComposerState
import app.kofipod.bookmarks.BookmarkRepository
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkComposerSheet() {
    val composer: BookmarkComposer = koinInject()
    val repo: BookmarkRepository = koinInject()
    val state by composer.state.collectAsState()
    val visible = state as? BookmarkComposerState.Visible ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = LocalKofipodColors.current
    var note by remember(visible.episodeId, visible.timestampMs) { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = { composer.cancel() },
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Bookmark", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "${visible.podcastTitle} · ${visible.episodeTitle}",
                color = c.textMute,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatTimestamp(visible.timestampMs),
                color = c.purple,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.bg)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (note.isEmpty()) {
                    Text("Add a note (optional)", color = c.textMute, fontSize = 14.sp)
                }
                BasicTextField(
                    value = note,
                    onValueChange = { if (it.length <= 280) note = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                KPButton(
                    label = "Cancel",
                    onClick = { composer.cancel() },
                    icon = KPIconName.Close,
                    primary = false,
                )
                Spacer(Modifier.height(0.dp))
                Row { Spacer(Modifier.width(8.dp)) }
                KPButton(
                    label = "Save",
                    onClick = {
                        repo.add(
                            episodeId = visible.episodeId,
                            podcastId = visible.podcastId,
                            timestampMs = visible.timestampMs,
                            note = note.trim().ifBlank { null },
                            nowMs = Clock.System.now().toEpochMilliseconds(),
                        )
                        composer.cancel()
                    },
                    icon = KPIconName.Check,
                    primary = true,
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

(`KPButton` — locate the live signature in `app.kofipod.ui.primitives` and adjust if `primary` / `icon` field names have drifted. The button surface in this codebase already supports primary/secondary — match the live API.)

(`androidx.compose.foundation.layout.width` — add the import if `KPButton` needs an explicit Spacer width; replace the `Row { Spacer(Modifier.width(8.dp)) }` with `Spacer(Modifier.width(8.dp))` and import `androidx.compose.foundation.layout.width`. The two-button row should have an 8.dp gap.)

- [ ] **Step 2: Hoist the sheet at AppShell**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`, add to the imports:

```kotlin
import app.kofipod.ui.screens.bookmarks.BookmarkComposerSheet
```

Below the existing `OpmlPickerHost()` / `BackupPickerHost()` block (around `AppShell.kt:152`), add:

```kotlin
    BookmarkComposerSheet()
```

The sheet self-gates on its own state — when `Hidden`, the function returns before any composition.

- [ ] **Step 3: Compile-only check**

Run: `./gradlew :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL on all three targets. iOS-clean is non-negotiable per CLAUDE.md.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarkComposerSheet.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt
git commit -m "slice1(bookmarks): quick-add ModalBottomSheet hoisted at AppShell"
```

---

## Task 9: Per-episode Saved section on EpisodeDetail

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt`

The Saved section is a sibling to the tab strip, NOT a fifth tab — see CLAUDE.md ("Tab strip stays four max"). Each row shows formatted timestamp, optional note, and seeks-or-plays on tap. A long-press deletes (matches existing `combinedClickable` patterns in the Library code).

- [ ] **Step 1: Add `bookmarksFlow` + `seekToBookmark` to EpisodeDetailViewModel**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt`:

Add to the imports:

```kotlin
import app.kofipod.bookmarks.Bookmark
import app.kofipod.bookmarks.BookmarkRepository
```

Add `private val bookmarks: BookmarkRepository,` to the constructor.

Below the `state` field, add:

```kotlin
    val bookmarks: kotlinx.coroutines.flow.StateFlow<List<Bookmark>> =
        this.bookmarks.observeForEpisode(episodeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

(Rename the param if needed to avoid the `this.bookmarks` clash — e.g. `private val bookmarkRepo: BookmarkRepository`.)

Add a method:

```kotlin
    /**
     * Seek-or-play behaviour matches [seekToChapter]. Re-uses the existing helper
     * by delegating directly — bookmarks and chapters share semantics.
     */
    fun seekToBookmark(timestampMs: Long) = seekToChapter(timestampMs)

    fun deleteBookmark(id: String) = this.bookmarks.deleteById(id)
```

(The body of `deleteBookmark` calls into `BookmarkRepository.deleteById` — the receiver name depends on the rename above.)

Update the Koin factory in `CommonModule.kt`:

```kotlin
        viewModel { (episodeId: String) ->
            EpisodeDetailViewModel(
                episodeId = episodeId,
                episodes = get<EpisodeSource>(),
                library = get(),
                playback = get(),
                downloads = get(),
                player = get(),
                sharer = get(),
                chapters = get(),
                aiConfig = get(),
                bookmarkRepo = get(),
            )
        }
```

(Mirror whatever name you settled on in the constructor.)

- [ ] **Step 2: Implement the Saved section composable**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.Bookmark
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedSection(
    bookmarks: List<Bookmark>,
    onTap: (Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty()) return
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Saved")
        Spacer(Modifier.height(8.dp))
        bookmarks.forEachIndexed { idx, b ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = { onTap(b.timestampMs) },
                        onLongClick = { onDelete(b.id) },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KPIcon(name = KPIconName.Bookmark, color = c.purple, size = 18.dp)
                Spacer(Modifier.height(0.dp))
                Spacer(Modifier.padding(start = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatHms(b.timestampMs),
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    if (!b.note.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            b.note,
                            color = c.textMute,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun formatHms(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

- [ ] **Step 3: Render the section in EpisodeDetailScreen**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt`, locate the `EpisodeDetailContent` composable's tab block (around lines 249–300, the `if (visibleTabs.isNotEmpty()) { ... }` block). After the closing brace of the tab content `when` block (so the Saved section sits BELOW the tab strip + content area, not inside it), collect bookmarks and render:

Add to the imports:

```kotlin
import app.kofipod.bookmarks.Bookmark
```

Inside `EpisodeDetailContent` near the bookmark collection:

```kotlin
        val bookmarksList by viewModel.bookmarks.collectAsState()
        if (bookmarksList.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SavedSection(
                bookmarks = bookmarksList,
                onTap = { ms -> viewModel.seekToBookmark(ms); if (!state.isCurrentEpisode) onOpenPlayer() },
                onDelete = viewModel::deleteBookmark,
            )
        }
```

(`viewModel` reference: depending on how `EpisodeDetailContent` is structured — split or not — pass the `bookmarks` list and callbacks down through the function signature instead of injecting the VM here. Match the existing pattern: if `EpisodeDetailContent` already takes individual fields like `chapters: List<EpisodeChapter>`, add `bookmarks: List<Bookmark>`, `onBookmarkTap: (Long) -> Unit`, `onBookmarkDelete: (String) -> Unit` to its signature and have `EpisodeDetailScreen` wire them up.)

- [ ] **Step 4: Run unit tests**

Run: `./gradlew :composeApp:testPlayDebugUnitTest :composeApp:testFossDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Compile + lint**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL across all three.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice1(bookmarks): per-episode Saved section + seek/delete"
```

---

## Task 10: Global Bookmarks screen + Library entry point

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

- [ ] **Step 1: Add the route**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt`, add:

```kotlin
    @Serializable data object Bookmarks : Route
```

- [ ] **Step 2: Implement BookmarksViewModel**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.bookmarks.BookmarkRepository
import app.kofipod.bookmarks.BookmarkWithContext
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.PlaybackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val rows: List<BookmarkWithContext> = emptyList(),
    val query: String = "",
)

class BookmarksViewModel(
    private val bookmarks: BookmarkRepository,
    private val player: KofipodPlayer,
    private val episodes: EpisodeSource,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<BookmarksUiState> =
        combine(bookmarks.observeAll(), query) { rows, q ->
            BookmarksUiState(
                rows = if (q.isBlank()) rows else rows.filter { it.matches(q) },
                query = q,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun setQuery(q: String) { query.value = q }

    fun delete(id: String) = bookmarks.deleteById(id)

    /**
     * Tap-to-play: starts the episode at the bookmark's timestamp. Reuses the
     * same path PlayerViewModel.step() takes — resolve a download URL or fall
     * back to streaming via DownloadRepository.resolvedSourceUrl.
     */
    fun openAt(row: BookmarkWithContext) {
        viewModelScope.launch {
            val ep = episodes.episodeFlow(row.bookmark.episodeId)
                // .first() blocks until first emission; intentional — bookmarks always
                // reference a real episode (FK constrained), so the flow lands quickly.
                .let { kotlinx.coroutines.flow.first(it) } ?: return@launch
            val sourceUrl = downloads.resolvedSourceUrl(ep.id, ep.enclosureUrl) ?: return@launch
            player.play(
                PlayableEpisode(
                    episodeId = ep.id,
                    podcastId = row.bookmark.podcastId,
                    podcastTitle = row.podcastTitle,
                    title = row.episodeTitle,
                    artworkUrl = row.artworkUrl,
                    sourceUrl = sourceUrl,
                    startPositionMs = row.bookmark.timestampMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }

    private fun BookmarkWithContext.matches(q: String): Boolean {
        val needle = q.trim().lowercase()
        return episodeTitle.lowercase().contains(needle) ||
            podcastTitle.lowercase().contains(needle) ||
            (bookmark.note?.lowercase()?.contains(needle) == true)
    }
}
```

(Confirm `episodes.episodeFlow(...)` returns `Flow<Episode?>` — it does, per `EpisodesRepository`. Adjust the `first` call site to match the project's existing pattern; if there's already a `episodeOnce(...)` suspend helper, prefer it.)

- [ ] **Step 3: Implement BookmarksScreen**

`composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.bookmarks.BookmarkWithContext
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: BookmarksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KPIcon(
                name = KPIconName.Back,
                color = c.text,
                size = 22.dp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).padding(4.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("Bookmarks", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        // TODO row click handler for the back arrow above — wrap KPIcon in a clickable {} that calls onBack.
        // TODO style the search field to match the existing top-of-Library pattern (BasicTextField + outline).
        SearchField(query = state.query, onChange = viewModel::setQuery)
        Spacer(Modifier.height(8.dp))

        if (state.rows.isEmpty()) {
            EmptyState(state.query.isNotBlank())
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.bookmark.id }) { row ->
                BookmarkRow(
                    row = row,
                    onTap = {
                        viewModel.openAt(row)
                        onOpenPlayer()
                    },
                    onLongPress = { viewModel.delete(row.bookmark.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Search, color = c.textMute, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search bookmarks…", color = c.textMute, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    row: BookmarkWithContext,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(14.dp),
    ) {
        Text(
            row.podcastTitle,
            color = c.textMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            row.episodeTitle,
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            formatHms(row.bookmark.timestampMs),
            color = c.purple,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        if (!row.bookmark.note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                row.bookmark.note,
                color = c.text,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (filtered) "No matches." else "No bookmarks yet.\nTap the bookmark icon while playing to save a moment.",
            color = c.textMute,
            fontSize = 14.sp,
        )
    }
}

private fun formatHms(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

(Two `TODO` lines above are intentional — the back-arrow's clickable wrapping and the polished search-field styling can be matched against the live Library top-of-screen treatment when that lands. Don't ship the file with `TODO` comments still in it; remove them once the wiring is done. The `Box` import for the SearchField placeholder is `androidx.compose.foundation.layout.Box` — add it.)

- [ ] **Step 4: Wire the route + Koin factory**

In `KofipodNavHost.kt`, add:

```kotlin
import app.kofipod.ui.screens.bookmarks.BookmarksScreen

// ... inside NavHost { ... }:
        composable<Route.Bookmarks> {
            BookmarksScreen(
                onBack = { navController.popBackStack() },
                onOpenPlayer = {
                    navController.navigate(
                        Route.Player,
                        NavOptions.Builder().setLaunchSingleTop(true).build(),
                    )
                },
            )
        }
```

In `CommonModule.kt`:

```kotlin
        viewModel {
            app.kofipod.ui.screens.bookmarks.BookmarksViewModel(
                bookmarks = get(),
                player = get(),
                episodes = get<EpisodeSource>(),
                playback = get(),
                downloads = get(),
            )
        }
```

- [ ] **Step 5: Add Library entry-point row + Pro gate**

In `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt`, mirror the Player VM pattern. Add `private val pro: ProEntitlementRepository,` and `private val paywallRouter: PaywallRouter,` to the constructor (lockstep update the Koin factory in `CommonModule.kt:290`), and:

```kotlin
    /**
     * Returns true if the navigation should proceed; false if the paywall was opened instead.
     * Same gate logic as PlayerViewModel.onBookmarkTapped.
     */
    fun onBookmarksTapped(): Boolean {
        return when (pro.state.value) {
            is ProEntitlement.Pro -> true
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> {
                paywallRouter.requestPaywall("paywall_bookmark")
                false
            }
        }
    }
```

In `LibraryScreen.kt`, add the entry-point row near the top of the existing list / below the search row. Pattern (insert near the existing row builders):

```kotlin
            EntryPointRow(
                icon = KPIconName.Bookmark,
                label = "Bookmarks",
                onClick = {
                    if (viewModel.onBookmarksTapped()) onOpenBookmarks()
                },
            )
```

`onOpenBookmarks: () -> Unit` is a new param on `LibraryScreen`. Plumb it from `KofipodNavHost.kt` via `navController.navigate(Route.Bookmarks)`.

`EntryPointRow` matches the existing Library row style — locate the closest pattern (e.g. how Stats or Starter Pack are surfaced) and reuse, NOT a new visual. If no precedent exists, write it inline as a `Row(Modifier.clickable { ... })` matching the `TabItem` shape from `AppShell`.

- [ ] **Step 6: Run lint + compile + tests**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testPlayDebugUnitTest :composeApp:testFossDebugUnitTest`
Expected: BUILD SUCCESSFUL on all.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice1(bookmarks): global Bookmarks screen + Library entry point"
```

---

## Task 11: Emulator verification (manual)

This task is a pre-merge gate. Per CLAUDE.md, "make sure to test the golden path and edge cases for the feature."

- [ ] **Step 1: Install both flavors on Pixel_9a**

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &
./gradlew :composeApp:installPlayDebug :composeApp:installFossDebug
```

- [ ] **Step 2: Verify Free path (Play flavor)**

1. Launch the Play flavor (default app icon).
2. Subscribe to a podcast, start an episode.
3. Open Player, tap bookmark icon.
4. **Expected:** Paywall sheet opens. Composer sheet does NOT show.
5. Dismiss paywall. Tap bookmark again — paywall reopens (no rate-limit, per Slice 0 contract).

- [ ] **Step 3: Verify Pro path (FOSS flavor)**

1. Launch the FOSS-flavor app (separate launcher entry; "Foss" suffix per Slice 0).
2. Subscribe to a podcast, start an episode, let it play 10s.
3. Tap bookmark.
4. **Expected:** Composer sheet opens at the bottom with podcast/episode/timestamp visible. Type a note, tap Save.
5. **Expected:** Sheet dismisses. Open Episode Detail — Saved section shows the new bookmark with the formatted timestamp + note.
6. Tap the saved row — playback seeks/plays at that timestamp.
7. Long-press the row — bookmark deletes.

- [ ] **Step 4: Verify Library entry point + global screen**

1. From Library (FOSS), tap the new "Bookmarks" entry-point row.
2. **Expected:** Global Bookmarks screen shows all bookmarks newest-first.
3. Type in the search field — list filters live.
4. Tap a row — opens player at the timestamp.
5. Long-press a row — deletes it.

- [ ] **Step 5: Verify Free Library entry point**

1. From Library (Play flavor), tap "Bookmarks".
2. **Expected:** Paywall sheet opens. Bookmarks screen does NOT show.

- [ ] **Step 6: Capture quick `adb` UI dump for the record**

Run: `~/Library/Android/sdk/platform-tools/adb shell uiautomator dump /sdcard/view.xml && ~/Library/Android/sdk/platform-tools/adb pull /sdcard/view.xml /tmp/`
Inspect `/tmp/view.xml` to confirm the bookmark icon button is present at the expected node. (Sanity check; not a green-or-red gate.)

- [ ] **Step 7: If everything green, commit a final emulator-verified marker**

```bash
git commit --allow-empty -m "slice1(bookmarks): emulator-verified on Pixel_9a (Play paywall + FOSS create/list/seek/delete)"
```

---

## Task 12: Code review pass

Per CLAUDE.md ("ALWAYS get your code reviewed by a sub agent with code review skills"):

- [ ] **Step 1: Dispatch a code review subagent**

Use the `feature-dev:code-reviewer` agent (or `superpowers:code-reviewer` if subagent-driven). Hand it:
- The git range `master..HEAD` for diffs.
- The plan file path so it can verify spec coverage.
- The CLAUDE.md sections on iOS-clean, no-table-level Auto Backup filtering, four-tab-strip rule, ktlint/detekt rules.

- [ ] **Step 2: Resolve any HIGH or CRITICAL findings**

If the reviewer flags real issues, fix them and re-run the green-check sequence. False positives can be dismissed with a one-line note in the review thread.

- [ ] **Step 3: Final green check**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testPlayDebugUnitTest :composeApp:testFossDebugUnitTest :composeApp:verifyPaparazziDebug`
Expected: BUILD SUCCESSFUL.

---

## Out of scope for this slice

- **Auto Backup** of the new table — `Bookmark` is part of `KofipodDatabase`, which is already in the included `database` domain. No `backup_rules.xml` change needed. (Per CLAUDE.md, Auto Backup operates at file/domain level, not table level.)
- **No Snippets coupling.** Bookmarks store no audio. The "Snip & share clips" feature is Slice 3.
- **No PKM export wiring.** The schema lays groundwork (createdAtMs ordering) but the export action is Slice 5+.
- **No FTS5.** Search inside Bookmarks is a dumb in-memory `contains` filter on the already-loaded list. Library-wide FTS arrives in Slice 2.
- **No telemetry counter.** Per spec, conversion attribution by `paywall_bookmark` trigger key is captured locally in Slice 0 already; nothing new lands here.

---

## Self-review notes

**Spec coverage check:**
- F2 Bookmarks behaviour spec lines 181–186 — all four bullets covered: tap-Bookmark, optional note, listed per-episode + global, seek-or-play.
- Schema Slice 1 row from spec line 320 — `id, episodeId (FK), podcastId (FK), timestampMs, note?, createdAt` — exact column set.
- Settings/Pro entry point — already wired in Slice 0; this slice flips the Player gate from snackbar to real action and adds the Library entry-point row.
- "Tab strip stays four max" — Saved is rendered as a sibling section below the existing tab content, not as a fifth tab. Confirmed.

**Type consistency:** `BookmarkRepository.add` returns `String` (the new id). `BookmarkComposer.requestQuickAdd(...)` parameter list matches the call site in `PlayerViewModel.onBookmarkTapped`. `EpisodeDetailViewModel.bookmarks` is `StateFlow<List<Bookmark>>` (domain type, not the SQLDelight row).

**Placeholder scan:** The `TODO` lines in `BookmarksScreen.kt` (back-arrow click handler + search-field polish) and `FakePlayerHarness.buildPlayerViewModel` are explicitly called out as deferred details for the implementer to resolve from live code, not unresolved plan placeholders.
