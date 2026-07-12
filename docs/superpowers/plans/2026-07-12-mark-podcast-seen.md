# Implicit "Mark Podcast Seen" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dismiss a podcast's library "new" indicator when the user opens its episode list and dwells ~1.5s, via a per-podcast `lastSeenAt` watermark — without marking any episode played, and keeping the existing play-to-dismiss behavior.

**Architecture:** Add a nullable `Podcast.lastSeenAt` column (epoch millis). The existing `selectNewEpisodeCountsByPodcast` query gains one clause so an episode is "new" only when never played AND published after `MAX(addedAt, lastSeenAt)`. `PodcastDetailScreen` fires `viewModel.markSeen()` after a `LaunchedEffect` dwell; cancellation-on-exit is the accidental-tap guard. Every "new" dot surface updates automatically because it reads the reactive `newEpisodeCountsFlow`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight, Koin, kotlinx.coroutines, kotlinx.datetime. Tests: kotlin.test + SQLDelight JDBC in-memory driver (JVM unit tests under `androidUnitTest`).

## Global Constraints

- New source files MUST start with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- `commonMain` must stay KMP-safe: no `java.*`, no `System.currentTimeMillis()`. Timestamps use `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()`.
- All three iOS targets must keep compiling. This change is `commonMain`-only and uses `Clock`, `kotlinx.coroutines.delay`, and `LaunchedEffect` (all KMP-safe), so iOS stays green by construction. Verify with `./gradlew :composeApp:compileKotlinIosSimulatorArm64` on macOS if available (Kotlin/Native iOS cannot compile on this Linux box — see note in Task 4).
- Migrations: add `N.sqm` (migration N moves schema N→N+1). After adding one, bump `DB_SCHEMA_VERSION` in `backup/Manifest.kt` in lockstep — `ManifestTest.dbSchemaVersion_matchesGeneratedSchema` fails otherwise. Repo is currently at schema **22** (highest migration `21.sqm`); this plan adds `22.sqm` → schema **23**.
- Pre-commit hook runs `ktlintFormat` + `detekt` on staged `.kt`/`.kts`. Stage cleanly (no mixed staged/unstaged edits in one file) before committing.
- All unit tests must pass before the work is declared done.
- Commit message trailers (repo convention):
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Kumnh6W38zHAnsWt11RhvP
  ```

---

### Task 1: Schema — `lastSeenAt` column, `setLastSeen` query, migration, version bump

**Files:**
- Modify: `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/Podcast.sq`
- Create: `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/migrations/22.sqm`
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/backup/Manifest.kt:194`
- Modify: `CLAUDE.md` (schema-version note)
- Modify (fix constructor calls broken by the new column — all use named args ending in `primaryCategory = …,`):
  - `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/pkm/PkmExportCoordinatorSlice6Test.kt`
  - `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/ui/screens/detail/EpisodeDetailMergeTest.kt`
  - `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/pkm/MarkdownFormatterTest.kt`
  - `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/repo/RemoteEpisodeCacheTest.kt`
  - `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/pkm/PkmExportCoordinatorTest.kt`
  - `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/screenshots/LibraryScreenSnapshots.kt`
  - `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/screenshots/EpisodeDetailScreenSnapshots.kt`
  - `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/screenshots/EpisodeDetailSnapshots.kt`
  - `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/ui/screens/detail/EpisodeDetailViewModelTest.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/backup/ManifestTest.kt` (existing — the drift guard)

**Interfaces:**
- Produces: `Podcast.lastSeenAt: Long?` (generated column), `db.podcastQueries.setLastSeen(lastSeenAt: Long?, id: String)` (generated query — note SQLDelight orders parameters by their appearance in the SQL: `SET lastSeenAt = ?` first, `WHERE id = ?` second).

- [ ] **Step 1: Add the `lastSeenAt` column to the `Podcast` table**

In `Podcast.sq`, add the column after `primaryCategory` and before the `FOREIGN KEY` line (a trailing nullable column; existing rows migrate to `NULL`):

```sql
CREATE TABLE Podcast (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    description TEXT NOT NULL,
    artworkUrl TEXT NOT NULL,
    feedUrl TEXT NOT NULL,
    listId TEXT,
    autoDownloadEnabled INTEGER NOT NULL DEFAULT 0,
    notifyNewEpisodesEnabled INTEGER NOT NULL DEFAULT 1,
    lastCheckedAt INTEGER,
    addedAt INTEGER NOT NULL,
    primaryCategory TEXT NOT NULL DEFAULT '',
    -- Per-podcast "seen" watermark (epoch millis). NULL = never opened since subscribing.
    -- Set to now when the user dwells on the podcast's episode list; the new-episode-count
    -- query treats NULL as 0 so behavior is unchanged until a podcast is first seen.
    lastSeenAt INTEGER,
    FOREIGN KEY (listId) REFERENCES PodcastList(id) ON DELETE SET NULL
);
```

- [ ] **Step 2: Add the `setLastSeen` query**

In `Podcast.sq`, add after the existing `setLastChecked` block:

```sql
setLastSeen:
UPDATE Podcast SET lastSeenAt = ? WHERE id = ?;
```

- [ ] **Step 3: Create migration `22.sqm`**

Create `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/migrations/22.sqm`:

```sql
-- Adds a per-podcast "seen" watermark (epoch millis). NULL = never opened since
-- subscribing; the new-episode-count query treats NULL as 0. Opening a podcast's
-- episode list and dwelling ~1.5s sets this to now, dismissing the "new" dot
-- without marking episodes played.
ALTER TABLE Podcast ADD COLUMN lastSeenAt INTEGER;
```

- [ ] **Step 4: Fix the broken `Podcast(...)` constructor call sites**

Every direct `Podcast(...)` construction in the 9 test/snapshot files listed above ends with a `primaryCategory = …,` argument. In each, add `lastSeenAt = null,` immediately after that line. Example (before → after) for `EpisodeDetailViewModelTest.kt`:

```kotlin
                addedAt = 0L,
                primaryCategory = "",
                lastSeenAt = null,
            )
```

Do the identical addition in all 9 files. (`db.podcastQueries.insert(...)` call sites do NOT need changes — `insert` lists explicit columns and omits `lastSeenAt`, which defaults to `NULL`.)

- [ ] **Step 5: Run the drift guard to confirm it FAILS (schema advanced, constant not yet bumped)**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.backup.ManifestTest"`
Expected: `dbSchemaVersion_matchesGeneratedSchema` FAILS — `KofipodDatabase.Schema.version` is now `23` (added `22.sqm`) but `DB_SCHEMA_VERSION` is still `22`. This proves the migration registered and the guard works. (If instead the module fails to *compile*, a `Podcast(...)` site from Step 4 was missed — fix it and re-run.)

- [ ] **Step 6: Bump `DB_SCHEMA_VERSION`**

In `backup/Manifest.kt:194`, change:

```kotlin
const val DB_SCHEMA_VERSION = 23
```

- [ ] **Step 7: Update the stale schema-version note in `CLAUDE.md`**

In `CLAUDE.md`, under "Data / schema", replace the sentence beginning "current schema version is **21**" so it reads **23**:

```markdown
- Migrations in `migrations/` — current schema version is **23** (= `max(N.sqm) + 1`, because migration `N.sqm` moves schema from N to N+1). Add a new `N.sqm` file rather than editing existing tables, then bump `DB_SCHEMA_VERSION` in `backup/Manifest.kt` in lockstep (drift is guarded by `ManifestTest.dbSchemaVersion_matchesGeneratedSchema`). Dev installs auto-migrate; if a migration ever fails on an emulator, uninstall and reinstall to rebuild from `Schema.create`.
```

- [ ] **Step 8: Run the drift guard to confirm it PASSES**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.backup.ManifestTest"`
Expected: PASS (all ManifestTest cases green; `23 == Schema.version`).

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/Podcast.sq \
        composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/migrations/22.sqm \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/backup/Manifest.kt \
        CLAUDE.md \
        composeApp/src/commonTest composeApp/src/androidUnitTest
git commit -m "feat: add Podcast.lastSeenAt column + setLastSeen query (schema 23)

Migration 22.sqm adds a nullable per-podcast 'seen' watermark. Bumps
DB_SCHEMA_VERSION in lockstep and fixes direct Podcast(...) test/snapshot
constructors for the new column.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kumnh6W38zHAnsWt11RhvP"
```

---

### Task 2: Watermark-aware new-episode-count query

**Files:**
- Modify: `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/Episode.sq:46-57`
- Test: `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/db/NewEpisodeCountTest.kt` (create)

**Interfaces:**
- Consumes: `Podcast.lastSeenAt`, `db.podcastQueries.setLastSeen(...)` (Task 1).
- Produces: unchanged query surface — `db.episodeQueries.selectNewEpisodeCountsByPodcast()` still returns rows of `(podcastId: String, newCount: Long)`. Only the WHERE semantics change.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/db/NewEpisodeCountTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.db

import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the semantics of the library "new" indicator: an episode is "new" when it has
 * no PlaybackState row (never started) AND was published after the later of
 * {podcast addedAt, podcast lastSeenAt}. Covers the watermark introduced for
 * implicit "mark seen" plus the retained play-to-dismiss channel.
 */
class NewEpisodeCountTest {
    private lateinit var db: KofipodDatabase

    @BeforeTest
    fun setUp() {
        db = inMemoryDatabase()
        // Podcast added at t=100. Three episodes: one before the add (back catalog),
        // two after.
        db.podcastQueries.insert(
            id = "p1", title = "Show", author = "", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 100,
            primaryCategory = "",
        )
        episode("eOld", publishedAt = 50)
        episode("eMid", publishedAt = 150)
        episode("eNew", publishedAt = 250)
    }

    private fun episode(id: String, publishedAt: Long) =
        db.episodeQueries.insert(
            id = id, podcastId = "p1", guid = id, title = id, description = "",
            publishedAt = publishedAt, durationSec = 1, enclosureUrl = "",
            enclosureMimeType = "audio/mpeg", fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )

    private fun counts(): Map<String, Long> =
        db.episodeQueries.selectNewEpisodeCountsByPodcast()
            .executeAsList()
            .associate { it.podcastId to it.newCount }

    private fun markPlayed(episodeId: String) =
        db.playbackStateQueries.upsert(
            episodeId = episodeId, positionMs = 0, durationMs = 1, completedAt = 1,
            playbackSpeed = 1.0, updatedAt = 1, episodeTitle = "", podcastId = "p1",
            podcastTitle = "", artworkUrl = "", sourceUrl = "", episodeNumber = null,
        )

    @Test
    fun nullWatermark_countsEpisodesPublishedAfterAddedAt() {
        // eOld (50) excluded by addedAt (100); eMid + eNew counted.
        assertEquals(mapOf("p1" to 2L), counts())
    }

    @Test
    fun watermark_excludesEpisodesPublishedAtOrBeforeIt() {
        db.podcastQueries.setLastSeen(200, "p1")
        // new = published > max(addedAt=100, lastSeenAt=200) = 200 → only eNew (250).
        assertEquals(mapOf("p1" to 1L), counts())
    }

    @Test
    fun watermarkAfterNewest_dropsPodcastEntirely() {
        db.podcastQueries.setLastSeen(300, "p1")
        // Nothing published after 300 → p1 absent from the result map.
        assertEquals(emptyMap(), counts())
    }

    @Test
    fun playbackRow_stillDismissesIndependentlyOfWatermark() {
        // No watermark set; mark eMid played. It drops even though published > addedAt,
        // proving play-to-dismiss survives alongside the watermark. eNew remains.
        markPlayed("eMid")
        assertEquals(mapOf("p1" to 1L), counts())
    }
}
```

- [ ] **Step 2: Run the test to verify it FAILS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.db.NewEpisodeCountTest"`
Expected: `watermark_excludesEpisodesPublishedAtOrBeforeIt` and `watermarkAfterNewest_dropsPodcastEntirely` FAIL — the current query ignores `lastSeenAt`, so both still report `p1 to 2L`. (The other two cases pass under the old query.)

- [ ] **Step 3: Update the query to honor the watermark**

In `Episode.sq`, replace the `selectNewEpisodeCountsByPodcast` block (lines 46-57) with:

```sql
-- Counts of "new" episodes per podcast: never started (no PlaybackState row) AND
-- published after the later of {podcast added to library, per-podcast "seen"
-- watermark}. The addedAt term stops the historical back catalog from flooding the
-- indicator on first subscribe; the lastSeenAt term lets opening the episode list
-- dismiss the current new episodes (COALESCE treats an unset watermark as 0, so
-- behavior is unchanged until a podcast is first seen). Podcasts with zero matches
-- are absent from the result.
selectNewEpisodeCountsByPodcast:
SELECT e.podcastId AS podcastId, COUNT(*) AS newCount
FROM Episode e
INNER JOIN Podcast p ON p.id = e.podcastId
LEFT JOIN PlaybackState ps ON ps.episodeId = e.id
WHERE ps.episodeId IS NULL
  AND e.publishedAt > MAX(p.addedAt, COALESCE(p.lastSeenAt, 0))
GROUP BY e.podcastId;
```

Note: `MAX(a, b)` here is SQLite's two-argument scalar max (not the aggregate — the aggregate takes one argument). If the SQLDelight compiler rejects the two-argument form during `generateDebugKofipodDatabaseInterface`, substitute the equivalent:
`AND e.publishedAt > (CASE WHEN p.lastSeenAt IS NOT NULL AND p.lastSeenAt > p.addedAt THEN p.lastSeenAt ELSE p.addedAt END)`

- [ ] **Step 4: Run the test to verify it PASSES**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.db.NewEpisodeCountTest"`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/Episode.sq \
        composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/db/NewEpisodeCountTest.kt
git commit -m "feat: honor lastSeenAt watermark in new-episode-count query

An episode is 'new' only when never played AND published after
MAX(addedAt, lastSeenAt). Play-to-dismiss is retained as an independent
channel. Covered by NewEpisodeCountTest.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kumnh6W38zHAnsWt11RhvP"
```

---

### Task 3: `LibraryRepository.markSeen`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/LibraryRepository.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/data/repo/LibraryRepositoryMarkSeenTest.kt` (create)

**Interfaces:**
- Consumes: `db.podcastQueries.setLastSeen(...)` (Task 1); `db.episodeQueries.selectNewEpisodeCountsByPodcast()` (Task 2).
- Produces: `LibraryRepository.markSeen(podcastId: String, seenAt: Long)` — writes the watermark; used by `PodcastDetailViewModel` in Task 4.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/data/repo/LibraryRepositoryMarkSeenTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRepositoryMarkSeenTest {
    private lateinit var db: KofipodDatabase
    private lateinit var repo: LibraryRepository

    @BeforeTest
    fun setUp() {
        db = inMemoryDatabase()
        repo = LibraryRepository(db)
        db.podcastQueries.insert(
            id = "p1", title = "Show", author = "", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 0,
            primaryCategory = "",
        )
    }

    @Test
    fun markSeen_persistsWatermark() {
        repo.markSeen("p1", 5_000L)
        assertEquals(5_000L, db.podcastQueries.selectById("p1").executeAsOne().lastSeenAt)
    }

    @Test
    fun markSeen_clearsNewCountForEpisodesPublishedBeforeIt() {
        // One episode published at t=1000 → new before markSeen.
        db.episodeQueries.insert(
            id = "e1", podcastId = "p1", guid = "g", title = "t", description = "",
            publishedAt = 1_000, durationSec = 1, enclosureUrl = "",
            enclosureMimeType = "audio/mpeg", fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )
        val before = db.episodeQueries.selectNewEpisodeCountsByPodcast().executeAsList()
        assertEquals(1, before.size)

        repo.markSeen("p1", 2_000L)

        val after = db.episodeQueries.selectNewEpisodeCountsByPodcast().executeAsList()
        assertEquals(0, after.size, "episode published (1000) before watermark (2000) must no longer be new")
    }
}
```

- [ ] **Step 2: Run the test to verify it FAILS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.data.repo.LibraryRepositoryMarkSeenTest"`
Expected: FAIL to compile — `LibraryRepository` has no `markSeen` method yet.

- [ ] **Step 3: Add the `markSeen` method**

In `LibraryRepository.kt`, add after the `setLastChecked` method (before `deletePodcast`):

```kotlin
    /**
     * Records that the user has "seen" this podcast's episodes as of [seenAt] (epoch
     * millis). Clears the library "new" dot for every episode published on or before
     * [seenAt] without marking any episode played. Idempotent; a no-op if the podcast
     * row does not exist.
     */
    fun markSeen(
        podcastId: String,
        seenAt: Long,
    ) = db.podcastQueries.setLastSeen(seenAt, podcastId)
```

- [ ] **Step 4: Run the test to verify it PASSES**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.data.repo.LibraryRepositoryMarkSeenTest"`
Expected: PASS (both cases).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/LibraryRepository.kt \
        composeApp/src/androidUnitTest/kotlin/com/kofikodr/kofipod/data/repo/LibraryRepositoryMarkSeenTest.kt
git commit -m "feat: LibraryRepository.markSeen writes the podcast seen-watermark

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kumnh6W38zHAnsWt11RhvP"
```

---

### Task 4: Wire the dwell trigger (VM + Screen) and final verification

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailScreen.kt`

**Interfaces:**
- Consumes: `LibraryRepository.markSeen(...)` (Task 3). `PodcastDetailViewModel` already injects `library: LibraryRepository` (constructor param 3) and imports `kotlinx.datetime.Clock` — no new constructor param, no Koin factory change.
- Produces: `PodcastDetailViewModel.markSeen()` — called by `PodcastDetailScreen`.

- [ ] **Step 1: Add `markSeen()` to `PodcastDetailViewModel`**

In `PodcastDetailViewModel.kt`, add next to the other library actions (near `toggleNotifyNewEpisodes`, ~line 410). It mirrors those actions: guard on library membership, then delegate.

```kotlin
    /**
     * Marks this podcast "seen", clearing its library "new" dot without marking any
     * episode played. Fired after a short dwell on the detail screen (see
     * PodcastDetailScreen). No-op when the podcast is not in the library — an
     * unsubscribed podcast has no row and no "new" indicator. Idempotent: sets the
     * watermark to the current time, so a re-entry (e.g. config change) is harmless.
     */
    fun markSeen() {
        if (!state.value.inLibrary) return
        library.markSeen(podcastId, Clock.System.now().toEpochMilliseconds())
    }
```

- [ ] **Step 2: Add the dwell-then-mark `LaunchedEffect` to `PodcastDetailScreen`**

In `PodcastDetailScreen.kt`:

(a) Add imports (ktlintFormat will order them):
```kotlin
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
```

(b) Add a file-level constant just above the `fun PodcastDetailScreen(` declaration (~line 71):
```kotlin
private const val SEEN_DWELL_MS = 1_500L
```

(c) Immediately after the state collectors at the top of the composable body — after
`val selectedEpisodeId by viewModel.selectedEpisodeId.collectAsState()` (~line 93) — add:
```kotlin
    LaunchedEffect(Unit) {
        // Dwell guard: this effect's coroutine is cancelled when the screen leaves
        // composition, so backing out before SEEN_DWELL_MS elapses skips the write —
        // an accidental tap-and-back won't dismiss the "new" dot. markSeen() is a
        // no-op off-library and idempotent, so re-entry is safe.
        delay(SEEN_DWELL_MS)
        viewModel.markSeen()
    }
```

- [ ] **Step 3: Compile + full unit-test + lint sweep**

Run each and confirm success:
```bash
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:ktlintFormat :composeApp:detekt
./gradlew :composeApp:verifyPaparazziDebug
```
Expected: all PASS. `verifyPaparazziDebug` needs no re-record — `lastSeenAt = null` in the snapshot factories does not change any rendered pixel, and the dot in snapshots is driven by static state params, not the live query. If `ktlintFormat` reorders imports in the two modified Kotlin files, re-stage them before committing.

iOS (run on macOS if available — cannot run on this Linux box):
```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
Expected: PASS. The change is `commonMain`-only using KMP-safe APIs (`Clock`, `delay`, `LaunchedEffect`).

- [ ] **Step 4: Emulator behavioral verification**

The dwell timing / cancel-on-exit is a Compose concern best verified live (per CLAUDE.md's emulator workflow). Build, install, and check both behaviors:

```bash
./gradlew :composeApp:installDebug
```

1. **Positive:** Subscribe to a podcast that has an episode published after you subscribed (so its tile shows the pink `NewDot` in Library). Tap into it, stay on the episode list ≥2s, press back. The `NewDot` is gone.
2. **Negative (accidental tap):** With another podcast showing a `NewDot`, tap in and immediately press back (<1s). The `NewDot` is still present.
3. **Independence:** Confirm playing / mark-played on a single episode still clears the dot as before (unchanged behavior).

Optionally confirm the watermark write at the DB level (see the emulator DB pull recipe in project memory): after step 1, `Podcast.lastSeenAt` for that podcast is non-NULL; after step 2, it is still NULL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailViewModel.kt \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailScreen.kt
git commit -m "feat: dismiss podcast 'new' dot after dwelling on its episode list

PodcastDetailScreen marks the podcast seen after a 1.5s dwell; the
LaunchedEffect's cancellation-on-exit guards accidental tap-and-back.
Play-to-dismiss is retained.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kumnh6W38zHAnsWt11RhvP"
```

---

## Self-Review

**Spec coverage:**
- Per-podcast `lastSeenAt` watermark storage → Task 1. ✅
- Query change `publishedAt > MAX(addedAt, COALESCE(lastSeenAt,0))` → Task 2. ✅
- `setLastSeen` write + `LibraryRepository.markSeen` → Tasks 1 & 3. ✅
- Dwell trigger in `PodcastDetailScreen`, cancel-on-exit guard, no new VM constructor param → Task 4. ✅
- Play-to-dismiss retained (independent channel) → asserted in Task 2 Step 1 `playbackRow_stillDismissesIndependentlyOfWatermark`. ✅
- Migration `22.sqm` + `DB_SCHEMA_VERSION` 22→23 lockstep + `ManifestTest` guard → Task 1. ✅
- Reactive surfaces update automatically (no per-surface work) → no task needed; `newEpisodeCountsFlow` is unchanged in shape, verified by existing Library VMs continuing to compile in Task 4 Step 3. ✅
- CLAUDE.md stale schema-version note → Task 1 Step 7. ✅
- Query behavior tests (before/after watermark, NULL = legacy, playback independence) → Task 2. ✅
- Accepted edge case (publish-during-dwell race) → design-only, no code; nothing to implement. ✅
- Backup `.kpbak` compatibility → additive nullable column; no code beyond the migration. ✅

**Placeholder scan:** No TBD/TODO; every code step shows complete code and exact commands with expected output. ✅

**Type consistency:** `markSeen(podcastId: String, seenAt: Long)` is defined identically in Task 3 (repo) and called with `(podcastId, Clock…toEpochMilliseconds())` in Task 4. `setLastSeen(lastSeenAt, id)` parameter order matches the SQL. `selectNewEpisodeCountsByPodcast()` row accessors `.podcastId`/`.newCount` used consistently in Tasks 2 & 3. ✅
