# Kofipod Pro — Slice 7: Smart Playlists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land Smart Playlists — user-defined saved filter definitions over the episode catalogue. Per spec § F6 (line 225), predicates cover: state (unplayed / in-progress / completed), duration range, podcast set, days-old, has-transcript, downloaded-only, has-snippets. Playlists surface as virtual rows in Library alongside existing folders/lists; tapping one opens a list of matched episodes. A full-screen editor builds the predicate visually with chip rows + a live "matches X episodes" preview. Predicate evaluator is pure Kotlin in `commonMain`.

**Architecture:** A new `SmartPlaylist` table (schema 20) stores `id`, `name`, `predicateJson`, `createdAt`. `SmartPlaylistPredicate` is a `@Serializable` flat record — each field an optional AND-filter. The evaluator (`PredicateEvaluator`) is pure-Kotlin and consumes a denormalised `EpisodeFacts` projection (one row per episode with the flags the predicate needs: completion state, downloaded?, transcript?, snippet count). `SmartPlaylistResolver` combines `EpisodeFactsRepository.observeAll()` with a single playlist's predicate to produce `Flow<List<MatchedEpisode>>`. The Library screen renders one virtual tile per playlist alongside existing list tiles; tapping navigates to `Route.SmartPlaylistDetail(playlistId)` (matched episode list) or `Route.SmartPlaylistEditor(playlistId?)` (create/edit). Both surfaces are Pro-gated through `paywallRouter.requestPaywall("paywall_smart_playlists")` from `LibraryViewModel`.

**Tech Stack:** Kotlin Multiplatform (`commonMain` + minimal android/ios actuals), Compose Multiplatform, SQLDelight v19 → v20 migration, Koin singletons + `viewModel { … }` factories, `kotlinx.serialization` (already on classpath), `kotlinx.datetime.Clock` (already the convention for timestamps), kotlin.test for commonTest unit coverage.

**Schema status:** Current is **19** (post-Slice 6 PkmConnection + ExportLog). Slice 7 lands at **20** by introducing `SmartPlaylist.sq` in a single `20.sqm` migration. This is the **last v1.0 schema bump**.

**Decisions locked here:**

- **Flat predicate, not boolean tree.** All predicate fields combine with AND. No OR / NOT in v1.0 — the spec's chip-row UI is naturally conjunctive, and `kotlinx.serialization` of a flat data class is simpler to migrate than a sealed expression tree.
- **`predicateJson` is opaque to SQLDelight.** Stored as `TEXT NOT NULL`. All read/write goes through `SmartPlaylistRepository`, which (de)serialises with the shared `kofipodJson` configured for `encodeDefaults = false; explicitNulls = false` so omitted predicate fields don't bloat the row or break forward-compat.
- **Predicate decoder is forgiving.** Unknown fields (`ignoreUnknownKeys = true`) and absent fields (defaults) so a future v1.1 predicate addition (e.g. `hasAiSummary`) doesn't crash older app versions reading a newer DB after Auto Backup restore.
- **Evaluator works over a denormalised `EpisodeFacts` projection**, not raw DB joins. `EpisodeFactsRepository` exposes `observeAll(): Flow<List<EpisodeFacts>>` over a single SQL projection that LEFT JOINs `Episode` × `PlaybackState` × `Download` × `Snippet` × `TranscriptCache`. Pure-Kotlin filter happens in commonMain — no SQL pushdown — so the same evaluator powers the editor's live count, the detail screen's matched list, and unit tests.
- **Auto Backup includes `SmartPlaylist`.** Already covered by `<include domain="database" />`; spec § "Auto Backup rules updated to include … SmartPlaylist" (line 356) confirms.
- **Editor save/cancel discards unsaved changes.** No autosave. The "matches X episodes" preview ticks live as chips toggle — uses a debounced flow off the in-memory predicate state, not the DB row.
- **Empty predicate (no chips selected) matches everything.** UX: a freshly-created playlist with no chips shows the whole episode catalogue, sorted newest-first. The user is expected to add chips immediately, but we don't block save.
- **Sort: `publishedAt DESC` only.** No user-pickable sort in v1.0. Matches the convention of every other episode list in the app.
- **Library tile shape mirrors the existing folder/list tile.** Smart Playlist tiles render with a distinguishing icon (`KPIconName.Sparkles` if it exists, else `KPIconName.Filter`) and the matched-count subtitle ("12 EPISODES"). Long-press → delete confirmation. They sit *after* the user's lists in the grid, before "New list".

**Spec references (verbatim):**

- `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` § F6 Smart Playlists (lines 225–230)
- § "Code architecture → New packages → playlists/" (line 322)
- § "Schema additions" Slice 8 row → realised here at v20 (per `e1487dc` SmartPlaylists is promoted into v1.0)
- § "Slice plan" Slice 7 row (line 394)
- § "New screens → Smart Playlist editor" (line 294)

**Out of scope (deferred):**

- Boolean trees (OR / NOT). Flat predicate suffices for the spec'd chip set; can be a future migration with a versioned `predicateJson` envelope.
- Sort other than `publishedAt DESC`.
- Manual reorder of playlist tiles in Library. v1.0 sorts by `createdAt ASC` (insertion order). Drag-reorder is a follow-up.
- Subscribing a playlist to download-on-match. Out of scope; the existing per-podcast `autoDownloadEnabled` flag covers the only user-pull mechanism we ship.
- Transcript-keyword predicate. The spec lists `has-transcript` (boolean), not "transcript contains X" — full-text search lives in Slice 2's Library Search.

---

## File structure

### Created

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/SmartPlaylist.sq` | Schema for the playlist registry. Columns: `id` (PK, TEXT — slugified name + dedup suffix), `name` (TEXT NOT NULL), `predicateJson` (TEXT NOT NULL — opaque to SQL), `createdAt` (INTEGER NOT NULL). Queries: `selectAll`, `selectById`, `upsert`, `delete`. |
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/20.sqm` | `CREATE TABLE SmartPlaylist ...`. Cold table; no backfill. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylist.kt` | Domain value type: `id`, `name`, `predicate: SmartPlaylistPredicate`, `createdAtMs: Long`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistPredicate.kt` | `@Serializable data class SmartPlaylistPredicate(state: PlayState? = null, durationRange: DurationRange? = null, podcastIds: Set<String>? = null, maxAgeDays: Int? = null, hasTranscript: Boolean? = null, downloadedOnly: Boolean? = null, hasSnippets: Boolean? = null)`. Companion: `EMPTY = SmartPlaylistPredicate()`. Nested `@Serializable enum class PlayState { Unplayed, InProgress, Completed }`. Nested `@Serializable data class DurationRange(minSec: Int? = null, maxSec: Int? = null)`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFacts.kt` | Plain data class projecting one episode's evaluator-relevant flags: `episodeId`, `podcastId`, `publishedAtMs`, `durationSec`, `transcriptUrl: String?`, `hasCachedTranscript: Boolean`, `hasSnippets: Boolean`, `isDownloaded: Boolean`, `playState: PlayState` (computed: completedAt non-null → Completed; positionMs > 0 → InProgress; else Unplayed). Pure data, no DB types. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepository.kt` | `interface EpisodeFactsRepository { fun observeAll(): Flow<List<EpisodeFacts>> }`. Single Flow combining the underlying tables; intended consumer is `SmartPlaylistResolver`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepositoryImpl.kt` | Default impl over `KofipodDatabase`. Combines `episodeQueries.selectAll()` (new query — see Modified) with `playbackStateQueries.selectAll()`, `downloadQueries.selectAll()`, `snippetQueries.selectAllForFacts` (new), `transcriptCacheQueries.selectAll()` into a single `Flow<List<EpisodeFacts>>` via `kotlinx.coroutines.flow.combine`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/PredicateEvaluator.kt` | Pure-Kotlin evaluator: `fun evaluate(predicate: SmartPlaylistPredicate, facts: List<EpisodeFacts>, nowMs: Long): List<EpisodeFacts>`. Applies each non-null predicate field as an AND filter; sorts result by `publishedAtMs DESC`. No side effects, no I/O. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepository.kt` | `interface SmartPlaylistRepository { fun observeAll(): Flow<List<SmartPlaylist>>; fun observe(id: String): Flow<SmartPlaylist?>; suspend fun save(playlist: SmartPlaylist); suspend fun delete(id: String) }`. Delegates JSON encode/decode. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryImpl.kt` | Default impl over `KofipodDatabase`. Uses the shared `kofipodJson` (or a slice-local Json with `ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false`). DB writes on `Dispatchers.Default`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistResolver.kt` | Composition seam: given a `SmartPlaylist`, returns `Flow<List<EpisodeFacts>>` of matching episodes. Wraps `EpisodeFactsRepository.observeAll()` + the `PredicateEvaluator` + a clock. Used by both editor (live count) and detail (matched list). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorScreen.kt` | Compose screen. Header: name field, "Save" / "Cancel" actions. Body: predicate chip rows (state / duration / podcasts / age / transcript / downloaded / snippets) with toggleable selection. Footer: live "Matches N episodes" preview with the first 5 episode titles previewed inline. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorViewModel.kt` | Holds in-memory `SmartPlaylistPredicate` + name draft. Subscribes to `SmartPlaylistResolver.observe(predicate)` for the live count. `save()` persists; `cancel()` discards. Pre-fills from `playlistId` if non-null (edit mode). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorUiState.kt` | Sealed/data state: name, predicate, matched-count, matched-preview (first 5 titles), validating, error. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistDetailScreen.kt` | Compose screen. Header: playlist name + matched count + edit / delete affordances. Body: matched-episode list (reuses `EpisodeRow` from existing podcast detail). Tap row → `Route.EpisodeDetail`. Empty state when zero matches. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistDetailViewModel.kt` | Single Flow over `SmartPlaylistResolver.observe(playlistId)`. `delete()` cascades. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/SmartPlaylistTile.kt` | Compose helper: tile that mirrors the existing `ListTile` chrome from `LibraryScreen.kt` but draws `KPIconName.Sparkles` (or `Filter` fallback) + playlist name + "N EPISODES" subtitle. Long-press → delete callback. |
| `composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistPredicateTest.kt` | Round-trip JSON encode/decode for all field combinations. Asserts: empty predicate encodes to `{}`; unknown JSON fields are ignored on decode; nullable fields omit cleanly. |
| `composeApp/src/commonTest/kotlin/app/kofipod/playlists/PredicateEvaluatorTest.kt` | Pure-Kotlin tests for each predicate dimension: state filters (unplayed/in-progress/completed), duration min/max, podcast set membership, maxAgeDays cutoff, hasTranscript via cached OR url, downloadedOnly, hasSnippets. Combination test (state=Unplayed AND maxAgeDays=7 AND downloadedOnly=true). Empty predicate matches all. Sort order assertion (`publishedAt DESC`). |
| `composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryTest.kt` | In-memory SQLDelight driver. Asserts: `save` upserts; `observe` emits; `delete` removes; predicate JSON round-trips through `predicateJson` column. |
| `composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistResolverTest.kt` | Fake `EpisodeFactsRepository` + fake repo. Asserts the editor live-count flow emits when facts change AND when predicate changes. |
| `composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorViewModelTest.kt` | Fake resolver + fake repo. Asserts: predicate-toggle drives matched-count update; `save` persists with current name/predicate; `cancel` does not persist; edit-mode pre-fills from existing playlist. |

### Modified

| Path | Change |
|---|---|
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/Episode.sq` | Add `selectAll: SELECT * FROM Episode;`. Used by `EpisodeFactsRepositoryImpl` to drive the global facts flow. |
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq` | Add `selectEpisodeIdsWithSnippets: SELECT DISTINCT episodeId FROM Snippet;` (used by facts join — cheap distinct via existing `snippet_byEpisode` index). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt` | Add `@Serializable data class SmartPlaylistEditor(val playlistId: String? = null) : Route` and `@Serializable data class SmartPlaylistDetail(val playlistId: String) : Route`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` | Add `composable<Route.SmartPlaylistEditor> { ... SmartPlaylistEditorScreen(...) }` and `composable<Route.SmartPlaylistDetail> { ... SmartPlaylistDetailScreen(...) }`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt` | Add a `Tile.SmartPlaylist(playlist, matchedCount)` variant to the existing `Tile` sealed interface; render via `SmartPlaylistTile`. Add a `Tile.NewSmartPlaylist` slot if the spec design tile shows one (otherwise a long-press / Settings affordance covers create — verified during Task 0). Tile order in the grid: existing lists → unfiled → smart playlists → new-list (if shown). Add `onOpenSmartPlaylistEditor` and `onOpenSmartPlaylistDetail` callbacks to the screen signature. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` | Combine `SmartPlaylistRepository.observeAll()` + per-playlist matched-count flow into the existing `LibraryUiState`. Add `onSmartPlaylistTapped(playlistId): Boolean` and `onCreateSmartPlaylistTapped(): Boolean` Pro-gates following the same pattern as `onBookmarksTapped` / `onLibrarySearchTapped`. New trigger key: `paywall_smart_playlists`. Add `deleteSmartPlaylist(id)`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` | Register `single { EpisodeFactsRepositoryImpl(get()) } bind EpisodeFactsRepository::class`, `single { SmartPlaylistRepositoryImpl(get()) } bind SmartPlaylistRepository::class`, `single { SmartPlaylistResolver(get(), get()) }`, `single { PredicateEvaluator() }`, `viewModel { SmartPlaylistEditorViewModel(get(), get(), get(), it.getOrNull<String>()) }` (positional id from nav), `viewModel { SmartPlaylistDetailViewModel(get(), get(), it.get<String>()) }`. Bump `LibraryViewModel` factory in lockstep with the new `SmartPlaylistRepository` dep. |

### Untouched

- `Bookmark.sq`, `BookmarkRepository.kt`, `Snippet.sq` queries other than the new `selectEpisodeIdsWithSnippets`, `LibrarySearchIndex.sq`, `TranscriptCache.sq` — read by the facts projection but not modified.
- `KofipodPlayer.kt`, `Sharer.kt`, `KofipodDatabase` schema for any non-`Episode`/`Snippet` table — unchanged.
- Backup rules — `<include domain="database" />` already covers the new table.
- Detekt config — no new Android-only artifact introduced.

---

## Task list

> **Slice 7 has 12 tasks.** Tasks 0–7 are the data + evaluator layer (TDD-heavy). Tasks 8–10 are UI wiring. Task 11 is the green-check + commit.

### Task 0: Capture design tiles for Slice 7 surfaces

**Why:** Spec § "Design doc as source of truth" (line 252) makes this mandatory before implementing any Pro UI. Slice 7 touches three new surfaces (Smart Playlist tile in Library, Smart Playlist editor full screen, Smart Playlist detail / matched-list).

**Files:**
- Create directory: `/tmp/kofipod-design-slice7/`

- [ ] **Step 1: Render the design doc with Playwright/Chromium**

Dispatch a `general-purpose` subagent with this prompt:

> Open `docs/kofipod-pro-ui-design.html` (in working directory `/Users/ebernie/dev/podman/.claude/worktrees/kofipodpro-pre0/`) using Playwright Chromium. Wait for the "Unpacking..." indicator to disappear. Find every tile labeled with one of: "Smart Playlist", "Smart Playlists", "Library" (the variant that shows playlist tiles), "Predicate", "Editor". For each matched tile, screenshot just that tile and save under `/tmp/kofipod-design-slice7/<short-slug>.png`. Return the full list of saved paths and any tile labels not found.

- [ ] **Step 2: Reference saved paths in slice plan**

Append the returned screenshot paths to this plan under a new `## Captured design tiles` section so each implementation task can link back to the relevant tile. If a labeled tile is missing from the design doc, note it explicitly so downstream tasks know to derive treatment from existing patterns (e.g. matched-episode list ≈ existing PodcastDetail episode rows).

- [ ] **Step 3: Commit (docs-only)**

```bash
git add docs/superpowers/plans/2026-05-07-kofipod-pro-slice-7-smart-playlists.md
git commit -m "slice7(playlists): capture design tiles for Slice 7 surfaces"
```

---

### Task 1: Schema 20 — `SmartPlaylist` table + migration

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/SmartPlaylist.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/20.sqm`

- [ ] **Step 1: Write `SmartPlaylist.sq`**

```sql
CREATE TABLE SmartPlaylist (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    predicateJson TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX SmartPlaylist_createdAt_idx ON SmartPlaylist(createdAt);

selectAll:
SELECT * FROM SmartPlaylist ORDER BY createdAt ASC;

selectById:
SELECT * FROM SmartPlaylist WHERE id = ? LIMIT 1;

upsert:
INSERT OR REPLACE INTO SmartPlaylist(id, name, predicateJson, createdAt)
VALUES (?, ?, ?, ?);

delete:
DELETE FROM SmartPlaylist WHERE id = ?;
```

- [ ] **Step 2: Write `20.sqm` migration**

```sql
CREATE TABLE SmartPlaylist (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    predicateJson TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX SmartPlaylist_createdAt_idx ON SmartPlaylist(createdAt);
```

- [ ] **Step 3: Bump schema version**

In `composeApp/build.gradle.kts`, locate the SQLDelight `databases { create("KofipodDatabase") { version = 19 ... } }` block (or the migration-driven schema setup) and bump to `20`. Verify with `git diff` that no other version literal needs updating.

- [ ] **Step 4: Compile to verify migration generates**

Run: `./gradlew :composeApp:generateFossDebugKofipodDatabaseInterface :composeApp:compileFossDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. Generated `app.kofipod.db.SmartPlaylist` Kotlin type appears in `build/generated/sqldelight/...`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/SmartPlaylist.sq \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/20.sqm \
        composeApp/build.gradle.kts
git commit -m "slice7(playlists): schema 20 — SmartPlaylist table"
```

---

### Task 2: `SmartPlaylistPredicate` domain types + JSON round-trip

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistPredicate.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylist.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistPredicateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SmartPlaylistPredicateTest.kt
package app.kofipod.playlists

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SmartPlaylistPredicateTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test fun emptyPredicateEncodesToEmptyObject() {
        assertEquals("{}", json.encodeToString(SmartPlaylistPredicate.serializer(), SmartPlaylistPredicate()))
    }

    @Test fun roundTripPopulated() {
        val original = SmartPlaylistPredicate(
            state = PlayState.Unplayed,
            durationRange = DurationRange(minSec = 600, maxSec = 3600),
            podcastIds = setOf("p1", "p2"),
            maxAgeDays = 7,
            hasTranscript = true,
            downloadedOnly = false,
            hasSnippets = null,
        )
        val wire = json.encodeToString(SmartPlaylistPredicate.serializer(), original)
        val decoded = json.decodeFromString(SmartPlaylistPredicate.serializer(), wire)
        assertEquals(original, decoded)
    }

    @Test fun unknownFieldsIgnoredOnDecode() {
        val wire = """{"state":"Completed","futureField":"ignored"}"""
        val decoded = json.decodeFromString(SmartPlaylistPredicate.serializer(), wire)
        assertEquals(PlayState.Completed, decoded.state)
    }

    @Test fun durationRangeEncodesNullableFields() {
        val onlyMin = DurationRange(minSec = 300, maxSec = null)
        val wire = json.encodeToString(DurationRange.serializer(), onlyMin)
        // Should NOT include "maxSec" key when null + explicitNulls=false
        assertEquals(false, wire.contains("maxSec"))
    }
}
```

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.SmartPlaylistPredicateTest"`
Expected: FAIL — types don't exist.

- [ ] **Step 2: Implement the domain types**

```kotlin
// SmartPlaylistPredicate.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.serialization.Serializable

@Serializable
enum class PlayState { Unplayed, InProgress, Completed }

@Serializable
data class DurationRange(
    val minSec: Int? = null,
    val maxSec: Int? = null,
)

@Serializable
data class SmartPlaylistPredicate(
    val state: PlayState? = null,
    val durationRange: DurationRange? = null,
    val podcastIds: Set<String>? = null,
    val maxAgeDays: Int? = null,
    val hasTranscript: Boolean? = null,
    val downloadedOnly: Boolean? = null,
    val hasSnippets: Boolean? = null,
) {
    companion object {
        val EMPTY = SmartPlaylistPredicate()
    }
}
```

```kotlin
// SmartPlaylist.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

data class SmartPlaylist(
    val id: String,
    val name: String,
    val predicate: SmartPlaylistPredicate,
    val createdAtMs: Long,
)
```

- [ ] **Step 3: Run tests green**

`./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.SmartPlaylistPredicateTest"` → 4/4 pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/ \
        composeApp/src/commonTest/kotlin/app/kofipod/playlists/
git commit -m "slice7(playlists): SmartPlaylistPredicate + SmartPlaylist domain types + JSON round-trip"
```

---

### Task 3: `EpisodeFacts` projection type + repository interface

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFacts.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepository.kt`
- Modify: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Episode.sq` (add `selectAll`)
- Modify: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq` (add `selectEpisodeIdsWithSnippets`)

- [ ] **Step 1: Add the new SQL queries**

`Episode.sq` — append:
```sql
selectAll:
SELECT * FROM Episode;
```

`Snippet.sq` — append:
```sql
selectEpisodeIdsWithSnippets:
SELECT DISTINCT episodeId FROM Snippet;
```

- [ ] **Step 2: Write `EpisodeFacts`**

```kotlin
// EpisodeFacts.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

data class EpisodeFacts(
    val episodeId: String,
    val podcastId: String,
    val publishedAtMs: Long,
    val durationSec: Int,
    val transcriptUrl: String?,
    val hasCachedTranscript: Boolean,
    val hasSnippets: Boolean,
    val isDownloaded: Boolean,
    val playState: PlayState,
)
```

- [ ] **Step 3: Write `EpisodeFactsRepository` interface**

```kotlin
// EpisodeFactsRepository.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.coroutines.flow.Flow

interface EpisodeFactsRepository {
    fun observeAll(): Flow<List<EpisodeFacts>>
}
```

- [ ] **Step 4: Compile-only check**

`./gradlew :composeApp:compileFossDebugKotlinAndroid` → BUILD SUCCESSFUL. Generated `selectAll`, `selectEpisodeIdsWithSnippets` Kotlin types are present.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFacts.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepository.kt \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/Episode.sq \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq
git commit -m "slice7(playlists): EpisodeFacts projection + repository interface"
```

---

### Task 4: `PredicateEvaluator` — pure-Kotlin filter

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/PredicateEvaluator.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/playlists/PredicateEvaluatorTest.kt`

This task is the heart of the slice. Write the test first, then the impl. **Use TDD with one failing test at a time.**

- [ ] **Step 1: Write the failing tests**

```kotlin
// PredicateEvaluatorTest.kt
package app.kofipod.playlists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredicateEvaluatorTest {
    private val evaluator = PredicateEvaluator()
    private val nowMs = 1_715_000_000_000L  // fixed clock for deterministic age

    private fun fact(
        id: String,
        podcastId: String = "pod1",
        publishedAtMs: Long = nowMs,
        durationSec: Int = 1800,
        transcriptUrl: String? = null,
        hasCachedTranscript: Boolean = false,
        hasSnippets: Boolean = false,
        isDownloaded: Boolean = false,
        state: PlayState = PlayState.Unplayed,
    ) = EpisodeFacts(id, podcastId, publishedAtMs, durationSec, transcriptUrl, hasCachedTranscript, hasSnippets, isDownloaded, state)

    @Test fun emptyPredicateMatchesAll() {
        val facts = listOf(fact("e1"), fact("e2"))
        assertEquals(2, evaluator.evaluate(SmartPlaylistPredicate.EMPTY, facts, nowMs).size)
    }

    @Test fun stateUnplayedFilters() {
        val facts = listOf(
            fact("u", state = PlayState.Unplayed),
            fact("ip", state = PlayState.InProgress),
            fact("c", state = PlayState.Completed),
        )
        val result = evaluator.evaluate(SmartPlaylistPredicate(state = PlayState.Unplayed), facts, nowMs)
        assertEquals(listOf("u"), result.map { it.episodeId })
    }

    @Test fun durationRangeMinMax() {
        val facts = listOf(
            fact("short", durationSec = 300),
            fact("mid",   durationSec = 1800),
            fact("long",  durationSec = 7200),
        )
        val result = evaluator.evaluate(
            SmartPlaylistPredicate(durationRange = DurationRange(minSec = 600, maxSec = 3600)),
            facts, nowMs,
        )
        assertEquals(listOf("mid"), result.map { it.episodeId })
    }

    @Test fun durationRangeOnlyMin() {
        val facts = listOf(fact("short", durationSec = 300), fact("long", durationSec = 7200))
        val result = evaluator.evaluate(
            SmartPlaylistPredicate(durationRange = DurationRange(minSec = 600, maxSec = null)),
            facts, nowMs,
        )
        assertEquals(listOf("long"), result.map { it.episodeId })
    }

    @Test fun podcastIdsFilter() {
        val facts = listOf(fact("a", podcastId = "p1"), fact("b", podcastId = "p2"))
        val result = evaluator.evaluate(
            SmartPlaylistPredicate(podcastIds = setOf("p1")),
            facts, nowMs,
        )
        assertEquals(listOf("a"), result.map { it.episodeId })
    }

    @Test fun emptyPodcastIdsSetMatchesAll() {
        // Defensive: an empty (not null) set should match all rather than zero.
        val facts = listOf(fact("a"), fact("b"))
        val result = evaluator.evaluate(
            SmartPlaylistPredicate(podcastIds = emptySet()),
            facts, nowMs,
        )
        assertEquals(2, result.size)
    }

    @Test fun maxAgeDaysCutoff() {
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        val facts = listOf(
            fact("recent", publishedAtMs = nowMs - sevenDaysMs / 2),
            fact("old",    publishedAtMs = nowMs - sevenDaysMs * 2),
        )
        val result = evaluator.evaluate(SmartPlaylistPredicate(maxAgeDays = 7), facts, nowMs)
        assertEquals(listOf("recent"), result.map { it.episodeId })
    }

    @Test fun hasTranscriptViaUrlOrCache() {
        val facts = listOf(
            fact("none"),
            fact("urlOnly", transcriptUrl = "https://x"),
            fact("cacheOnly", hasCachedTranscript = true),
            fact("both", transcriptUrl = "https://x", hasCachedTranscript = true),
        )
        val result = evaluator.evaluate(SmartPlaylistPredicate(hasTranscript = true), facts, nowMs)
        assertEquals(setOf("urlOnly", "cacheOnly", "both"), result.map { it.episodeId }.toSet())
    }

    @Test fun hasTranscriptFalseFiltersOnlyMissing() {
        val facts = listOf(
            fact("none"),
            fact("urlOnly", transcriptUrl = "https://x"),
        )
        val result = evaluator.evaluate(SmartPlaylistPredicate(hasTranscript = false), facts, nowMs)
        assertEquals(listOf("none"), result.map { it.episodeId })
    }

    @Test fun downloadedOnlyTrueFilters() {
        val facts = listOf(fact("dl", isDownloaded = true), fact("nodl"))
        val result = evaluator.evaluate(SmartPlaylistPredicate(downloadedOnly = true), facts, nowMs)
        assertEquals(listOf("dl"), result.map { it.episodeId })
    }

    @Test fun downloadedOnlyFalseIsNoOp() {
        // A `false` value semantically means "the user did NOT pick this chip" — should match all.
        // The chip is bistable: present → filter; absent → null. We model this in the VM by
        // setting null when toggled off, but defensively the evaluator must not over-filter
        // on `downloadedOnly = false`.
        val facts = listOf(fact("dl", isDownloaded = true), fact("nodl"))
        val result = evaluator.evaluate(SmartPlaylistPredicate(downloadedOnly = false), facts, nowMs)
        assertEquals(2, result.size)
    }

    @Test fun hasSnippetsFiltersBothDirections() {
        val facts = listOf(fact("withS", hasSnippets = true), fact("noS"))
        assertEquals(listOf("withS"), evaluator.evaluate(SmartPlaylistPredicate(hasSnippets = true), facts, nowMs).map { it.episodeId })
        assertEquals(listOf("noS"),  evaluator.evaluate(SmartPlaylistPredicate(hasSnippets = false), facts, nowMs).map { it.episodeId })
    }

    @Test fun multiplePredicatesAreAndCombined() {
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        val facts = listOf(
            fact("match",        publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = true, state = PlayState.Unplayed),
            fact("wrongState",   publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = true, state = PlayState.Completed),
            fact("notDownloaded",publishedAtMs = nowMs - sevenDaysMs / 2, isDownloaded = false, state = PlayState.Unplayed),
            fact("tooOld",       publishedAtMs = nowMs - sevenDaysMs * 2, isDownloaded = true, state = PlayState.Unplayed),
        )
        val result = evaluator.evaluate(
            SmartPlaylistPredicate(state = PlayState.Unplayed, maxAgeDays = 7, downloadedOnly = true),
            facts, nowMs,
        )
        assertEquals(listOf("match"), result.map { it.episodeId })
    }

    @Test fun resultsSortedByPublishedAtDesc() {
        val facts = listOf(
            fact("oldest",   publishedAtMs = 100L),
            fact("middle",   publishedAtMs = 200L),
            fact("newest",   publishedAtMs = 300L),
        )
        val result = evaluator.evaluate(SmartPlaylistPredicate.EMPTY, facts, nowMs)
        assertEquals(listOf("newest", "middle", "oldest"), result.map { it.episodeId })
    }
}
```

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.PredicateEvaluatorTest"` → FAIL (class missing).

- [ ] **Step 2: Implement `PredicateEvaluator`**

```kotlin
// PredicateEvaluator.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

class PredicateEvaluator {
    fun evaluate(
        predicate: SmartPlaylistPredicate,
        facts: List<EpisodeFacts>,
        nowMs: Long,
    ): List<EpisodeFacts> {
        val cutoff = predicate.maxAgeDays?.let { nowMs - it * MS_PER_DAY }
        return facts
            .filter { f ->
                val statePass = predicate.state?.let { it == f.playState } ?: true
                val minPass = predicate.durationRange?.minSec?.let { f.durationSec >= it } ?: true
                val maxPass = predicate.durationRange?.maxSec?.let { f.durationSec <= it } ?: true
                val podPass = predicate.podcastIds?.let { ids -> ids.isEmpty() || f.podcastId in ids } ?: true
                val agePass = cutoff?.let { f.publishedAtMs >= it } ?: true
                val transcriptPass = predicate.hasTranscript?.let { wanted ->
                    val has = !f.transcriptUrl.isNullOrBlank() || f.hasCachedTranscript
                    has == wanted
                } ?: true
                val dlPass = if (predicate.downloadedOnly == true) f.isDownloaded else true
                val snipPass = predicate.hasSnippets?.let { it == f.hasSnippets } ?: true
                statePass && minPass && maxPass && podPass && agePass && transcriptPass && dlPass && snipPass
            }
            .sortedByDescending { it.publishedAtMs }
    }

    private companion object {
        const val MS_PER_DAY: Long = 24 * 60 * 60 * 1000L
    }
}
```

- [ ] **Step 3: Run tests green**

`./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.PredicateEvaluatorTest"` → all pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/PredicateEvaluator.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/playlists/PredicateEvaluatorTest.kt
git commit -m "slice7(playlists): PredicateEvaluator + 13 tests covering each predicate dimension"
```

---

### Task 5: `EpisodeFactsRepositoryImpl` — single Flow over the joined tables

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepositoryImpl.kt`

This task does NOT have a unit test in `commonTest` because the impl is a thin Flow-combine over SQLDelight, which would require an in-memory driver fixture that mirrors the entire schema. The existing repository pattern (`PlaybackRepository`, `DownloadRepository`) does not unit-test its Flow assembly either. Coverage is provided by `SmartPlaylistResolverTest` (Task 7) using a fake `EpisodeFactsRepository`.

- [ ] **Step 1: Implement**

```kotlin
// EpisodeFactsRepositoryImpl.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class EpisodeFactsRepositoryImpl(private val db: KofipodDatabase) : EpisodeFactsRepository {
    override fun observeAll(): Flow<List<EpisodeFacts>> =
        combine(
            db.episodeQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.playbackStateQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.downloadQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
            db.snippetQueries.selectEpisodeIdsWithSnippets().asFlow().mapToList(Dispatchers.Default),
            db.transcriptCacheQueries.selectAll().asFlow().mapToList(Dispatchers.Default),
        ) { episodes, playbackStates, downloads, snippetEpisodeIds, transcripts ->
            val playbackByEp = playbackStates.associateBy { it.episodeId }
            val downloadByEp = downloads.associateBy { it.episodeId }
            val snippetEps = snippetEpisodeIds.toHashSet()
            val transcriptEps = transcripts.map { it.episodeId }.toHashSet()
            episodes.map { e ->
                val ps = playbackByEp[e.id]
                val state = when {
                    ps?.completedAt != null -> PlayState.Completed
                    (ps?.positionMs ?: 0L) > 0L -> PlayState.InProgress
                    else -> PlayState.Unplayed
                }
                val dl = downloadByEp[e.id]
                EpisodeFacts(
                    episodeId = e.id,
                    podcastId = e.podcastId,
                    publishedAtMs = e.publishedAt,
                    durationSec = e.durationSec.toInt(),
                    transcriptUrl = e.transcriptUrl,
                    hasCachedTranscript = e.id in transcriptEps,
                    hasSnippets = e.id in snippetEps,
                    isDownloaded = dl?.state == "Completed",
                    playState = state,
                )
            }
        }
}
```

> **Note for implementer:** `TranscriptCache.sq` may not have a `selectAll` query; check first. If absent, add `selectAll: SELECT * FROM TranscriptCache;` to it. `Download.sq`'s `selectAll` exists. `PlaybackState.sq`'s `selectAll` exists. The `state == "Completed"` string match mirrors `Download.sq`'s `localPathFor` query convention (download state is a string column).

- [ ] **Step 2: Compile-only check**

`./gradlew :composeApp:compileFossDebugKotlinAndroid` → BUILD SUCCESSFUL. Also check iOS: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepositoryImpl.kt \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/TranscriptCache.sq  # iff modified
git commit -m "slice7(playlists): EpisodeFactsRepositoryImpl — single Flow over joined episode tables"
```

---

### Task 6: `SmartPlaylistRepository` + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryImpl.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryTest.kt` (NOT `commonTest` — `JdbcSqliteDriver` is JVM-only; matches Task 5's reasoning from Slice 6)

- [ ] **Step 1: Write the failing test**

```kotlin
// SmartPlaylistRepositoryTest.kt
package app.kofipod.playlists

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartPlaylistRepositoryTest {
    private fun newRepo(): SmartPlaylistRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        return SmartPlaylistRepositoryImpl(KofipodDatabase(driver))
    }

    @Test fun saveAndObserve() = runTest {
        val repo = newRepo()
        val pl = SmartPlaylist("p1", "Recent unplayed", SmartPlaylistPredicate(state = PlayState.Unplayed, maxAgeDays = 7), 1L)
        repo.save(pl)
        val observed = repo.observe("p1").first()
        assertEquals(pl, observed)
    }

    @Test fun observeAllOrdersByCreatedAtAsc() = runTest {
        val repo = newRepo()
        repo.save(SmartPlaylist("a", "A", SmartPlaylistPredicate.EMPTY, 200L))
        repo.save(SmartPlaylist("b", "B", SmartPlaylistPredicate.EMPTY, 100L))
        val all = repo.observeAll().first()
        assertEquals(listOf("b", "a"), all.map { it.id })
    }

    @Test fun deleteRemoves() = runTest {
        val repo = newRepo()
        repo.save(SmartPlaylist("p1", "n", SmartPlaylistPredicate.EMPTY, 1L))
        repo.delete("p1")
        assertNull(repo.observe("p1").first())
    }

    @Test fun roundTripPredicateThroughPersistence() = runTest {
        val repo = newRepo()
        val pred = SmartPlaylistPredicate(
            state = PlayState.InProgress,
            durationRange = DurationRange(minSec = 60, maxSec = 600),
            podcastIds = setOf("podA"),
            hasSnippets = true,
        )
        repo.save(SmartPlaylist("p1", "Mix", pred, 1L))
        val observed = repo.observe("p1").first()
        assertEquals(pred, observed?.predicate)
    }
}
```

- [ ] **Step 2: Write the interface + impl**

```kotlin
// SmartPlaylistRepository.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.coroutines.flow.Flow

interface SmartPlaylistRepository {
    fun observeAll(): Flow<List<SmartPlaylist>>
    fun observe(id: String): Flow<SmartPlaylist?>
    suspend fun save(playlist: SmartPlaylist)
    suspend fun delete(id: String)
}
```

```kotlin
// SmartPlaylistRepositoryImpl.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.kofipod.db.SmartPlaylist as DbSmartPlaylist

class SmartPlaylistRepositoryImpl(private val db: KofipodDatabase) : SmartPlaylistRepository {
    private val q = db.smartPlaylistQueries
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun observeAll(): Flow<List<SmartPlaylist>> =
        q.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<SmartPlaylist?> =
        q.selectById(id).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.toDomain() }

    override suspend fun save(playlist: SmartPlaylist) = withContext(Dispatchers.Default) {
        q.upsert(
            id = playlist.id,
            name = playlist.name,
            predicateJson = json.encodeToString(SmartPlaylistPredicate.serializer(), playlist.predicate),
            createdAt = playlist.createdAtMs,
        )
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        q.delete(id)
    }

    private fun DbSmartPlaylist.toDomain(): SmartPlaylist {
        val predicate = runCatching { json.decodeFromString(SmartPlaylistPredicate.serializer(), predicateJson) }
            .getOrElse { SmartPlaylistPredicate.EMPTY }
        return SmartPlaylist(id, name, predicate, createdAt)
    }
}
```

- [ ] **Step 3: Run tests green**

`./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.SmartPlaylistRepositoryTest"` → 4/4 pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepository.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryImpl.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/playlists/SmartPlaylistRepositoryTest.kt
git commit -m "slice7(playlists): SmartPlaylistRepository + JSON round-trip persistence test"
```

---

### Task 7: `SmartPlaylistResolver` — composition seam + test

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistResolver.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SmartPlaylistResolverTest.kt
package app.kofipod.playlists

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartPlaylistResolverTest {
    private val nowMs = 1_715_000_000_000L
    private val fakeClock = object : Clock { override fun now() = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs) }

    private class FakeFacts(val flow: MutableStateFlow<List<EpisodeFacts>>) : EpisodeFactsRepository {
        override fun observeAll() = flow
    }

    @Test fun emitsMatchedFactsForPredicate() = runTest {
        val facts = MutableStateFlow(listOf(
            EpisodeFacts("u", "p", nowMs, 1800, null, false, false, false, PlayState.Unplayed),
            EpisodeFacts("c", "p", nowMs, 1800, null, false, false, false, PlayState.Completed),
        ))
        val resolver = SmartPlaylistResolver(FakeFacts(facts), PredicateEvaluator(), fakeClock)
        val result = resolver.observe(SmartPlaylistPredicate(state = PlayState.Unplayed)).first()
        assertEquals(listOf("u"), result.map { it.episodeId })
    }

    @Test fun reEmitsWhenFactsChange() = runTest {
        val facts = MutableStateFlow(listOf<EpisodeFacts>())
        val resolver = SmartPlaylistResolver(FakeFacts(facts), PredicateEvaluator(), fakeClock)
        val emissions = mutableListOf<List<String>>()
        val job = kotlinx.coroutines.GlobalScope.launch {
            resolver.observe(SmartPlaylistPredicate.EMPTY).take(2).toList().forEach {
                emissions += it.map { f -> f.episodeId }
            }
        }
        facts.value = listOf(EpisodeFacts("a", "p", nowMs, 1800, null, false, false, false, PlayState.Unplayed))
        job.join()
        assertEquals(listOf(emptyList(), listOf("a")), emissions)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// SmartPlaylistResolver.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

class SmartPlaylistResolver(
    private val facts: EpisodeFactsRepository,
    private val evaluator: PredicateEvaluator,
    private val clock: Clock = Clock.System,
) {
    fun observe(predicate: SmartPlaylistPredicate): Flow<List<EpisodeFacts>> =
        facts.observeAll().map { all ->
            evaluator.evaluate(predicate, all, clock.now().toEpochMilliseconds())
        }
}
```

- [ ] **Step 3: Run tests green**

`./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.playlists.SmartPlaylistResolverTest"` → both pass.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/playlists/SmartPlaylistResolver.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/playlists/SmartPlaylistResolverTest.kt
git commit -m "slice7(playlists): SmartPlaylistResolver + Flow-recomposition test"
```

---

### Task 8: Routes + Koin wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

- [ ] **Step 1: Routes**

Append to `Routes.kt`:
```kotlin
@Serializable data class SmartPlaylistEditor(val playlistId: String? = null) : Route

@Serializable data class SmartPlaylistDetail(val playlistId: String) : Route
```

- [ ] **Step 2: Koin bindings in `CommonModule.kt`**

Add (locate the appropriate section — repositories grouped together, viewModels grouped together):

```kotlin
single<EpisodeFactsRepository> { EpisodeFactsRepositoryImpl(get()) }
single { PredicateEvaluator() }
single<SmartPlaylistRepository> { SmartPlaylistRepositoryImpl(get()) }
single { SmartPlaylistResolver(get(), get()) }

// VM bindings will land in Task 9; do not add yet (the VM classes don't exist).
```

> **Note for implementer:** Bump `LibraryViewModel`'s factory in `CommonModule.kt` only AFTER Task 10's signature change lands; otherwise the factory and constructor will desync. Task 8 just adds the new singletons.

- [ ] **Step 3: Compile check**

`./gradlew :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64` → both BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice7(playlists): routes + repository / resolver / evaluator Koin bindings"
```

---

### Task 9: Editor screen + ViewModel

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorUiState.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorScreen.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistEditorViewModelTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` (add `composable<Route.SmartPlaylistEditor> { ... }`)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (add `viewModel { SmartPlaylistEditorViewModel(...) }`)

This is the largest task. Dispatch a code-architect-tier subagent with **the full task text below** + the captured Task-0 design tile path. The implementer should follow TDD: write the VM test first, implement VM, then build the screen on top.

**ViewModel contract:**

```kotlin
class SmartPlaylistEditorViewModel(
    private val repo: SmartPlaylistRepository,
    private val resolver: SmartPlaylistResolver,
    private val podcasts: LibraryRepository,    // for the "podcasts" chip — list of Podcast names + ids
    private val playlistId: String?,            // null = create mode
    private val clock: Clock = Clock.System,
) : ViewModel() {
    val state: StateFlow<SmartPlaylistEditorUiState>
    fun setName(name: String)
    fun toggleState(state: PlayState?)             // null → clear
    fun setDurationRange(range: DurationRange?)
    fun togglePodcast(podcastId: String)           // toggles membership in podcastIds set
    fun clearPodcasts()
    fun setMaxAgeDays(days: Int?)
    fun toggleHasTranscript()                      // null → true → false → null
    fun toggleDownloadedOnly()                     // null → true → null
    fun toggleHasSnippets()                        // null → true → false → null
    fun save(): Boolean   // returns true on success; false if name blank
    fun delete()          // edit mode only; no-op in create mode
}
```

**UiState shape:**

```kotlin
data class SmartPlaylistEditorUiState(
    val name: String = "",
    val predicate: SmartPlaylistPredicate = SmartPlaylistPredicate.EMPTY,
    val matchedCount: Int = 0,
    val matchedPreview: List<String> = emptyList(),     // first 5 episode titles
    val availablePodcasts: List<PodcastSummary> = emptyList(),
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

data class PodcastSummary(val id: String, val title: String)
```

> The "matched preview" requires episode titles, but `EpisodeFacts` only has IDs. The implementer should either (a) extend `EpisodeFacts` with an episode title field (cheap — add it to the projection in `EpisodeFactsRepositoryImpl`), or (b) keep `EpisodeFacts` lean and join titles in the VM via a separate `EpisodesRepository.episodeNow(id)` lookup at preview time. **Recommendation: (a) — keep title in the projection.** Update `EpisodeFacts` to add `episodeTitle: String`, update `PredicateEvaluator` (no behaviour change), update `EpisodeFactsRepositoryImpl` to populate from `Episode.title`, update the Task-3 tests to pass a default title. The cost is ~5 lines and removes a per-frame DB lookup.

**Editor screen layout (rough):**

- TopAppBar with "Cancel" / "Save" actions; "Save" disabled when `name.isBlank()`.
- Name field at top.
- One predicate chip row per dimension:
  - **State**: tri-state segmented (Any / Unplayed / In progress / Completed).
  - **Duration**: chip "Any duration" → tap opens a min/max minute picker (two `TextField`s with int validation, or a `Slider` range pair). For v1.0 simplicity, two text fields suffice.
  - **Podcasts**: chip "All podcasts" → tap opens a multi-select sheet listing `availablePodcasts`. Selected count badge.
  - **Age**: chip "Any age" → tap opens a numeric input (max age in days). Predefined chips: 7 / 30 / 90.
  - **Transcript**: tri-state chip "Any" → "Has transcript" → "No transcript" → "Any".
  - **Downloaded only**: bistable chip "Any" → "Downloaded".
  - **Has snippets**: tri-state chip like Transcript.
- Footer: "Matches N episodes" pill + first up-to-5 titles preview list.
- Edit mode: "Delete playlist" destructive button at the bottom.

**ViewModel test (`SmartPlaylistEditorViewModelTest.kt`) MUST cover:**

1. Create-mode initial state has empty name + EMPTY predicate + matchedCount equal to total episodes (when facts has any).
2. `setName("Walks")` updates state.name.
3. `toggleState(PlayState.Unplayed)` updates predicate AND triggers a new matchedCount.
4. `togglePodcast("p1")` adds; second call removes.
5. `save()` with blank name returns false and writes nothing to repo.
6. `save()` with valid name calls `repo.save(...)` with current name + predicate + non-zero `createdAtMs`.
7. Edit-mode (`playlistId = "x"`) pre-fills `state.name` and `state.predicate` from the repo.
8. `delete()` in edit mode calls `repo.delete("x")`.

Use fakes for `SmartPlaylistRepository` (in-memory map), `SmartPlaylistResolver` (pass through a fixed `EpisodeFactsRepository`), and `LibraryRepository` (returns 2-3 podcasts). Tests run on `runTest`.

- [ ] **Step 1–6: TDD loop per VM behaviour above.**
- [ ] **Step 7: Build the Compose screen** consuming the VM.
- [ ] **Step 8: Wire `composable<Route.SmartPlaylistEditor>` in `KofipodNavHost.kt`** with `playlistId = entry.toRoute<Route.SmartPlaylistEditor>().playlistId` passed as a parameterised Koin arg via `viewModel { params -> SmartPlaylistEditorViewModel(get(), get(), get(), params.getOrNull<String>()) }`.
- [ ] **Step 9: Compile + tests green**

`./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.ui.screens.playlists.*" :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64` → all pass.

- [ ] **Step 10: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/ \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/ui/screens/playlists/ \
        composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFacts.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/playlists/EpisodeFactsRepositoryImpl.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/playlists/PredicateEvaluatorTest.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice7(playlists): SmartPlaylistEditor screen + ViewModel + 8 tests"
```

---

### Task 10: Detail screen + Library virtual rows + Pro-gating

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistDetailViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/SmartPlaylistDetailScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/SmartPlaylistTile.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt` (new tile variant + handlers)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` (combine playlist flow + Pro gates)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` (add detail composable + pass new callbacks to LibraryScreen)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (add detail VM binding; bump LibraryViewModel factory)

**`LibraryViewModel` extension:**

```kotlin
class LibraryViewModel(
    private val repo: LibraryRepository,
    episodes: EpisodeSource,
    stats: StatsRepository,
    private val opml: OpmlController,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val playlists: SmartPlaylistRepository,        // NEW
    private val resolver: SmartPlaylistResolver,            // NEW — for matched counts
) : ViewModel() {
    // Extend LibraryUiState with: val smartPlaylists: List<SmartPlaylistTile> = emptyList()
    // where SmartPlaylistTile = (id, name, matchedCount).
    // Combine flows in stateIn block.

    fun onSmartPlaylistTapped(id: String): Boolean = pro.gate("paywall_smart_playlists")
    fun onCreateSmartPlaylistTapped(): Boolean = pro.gate("paywall_smart_playlists")
    fun deleteSmartPlaylist(id: String) { viewModelScope.launch { playlists.delete(id) } }

    private fun ProEntitlementRepository.gate(triggerKey: String): Boolean = when (state.value) {
        is ProEntitlement.Pro -> true
        else -> { paywallRouter.requestPaywall(triggerKey); false }
    }
}
```

**`LibraryScreen` extension:**

Add a `Tile.SmartPlaylist(playlist, count)` to the existing `Tile` sealed interface; render via `SmartPlaylistTile`. Insert tiles into the grid AFTER existing list tiles + Unfiled, BEFORE the `NewList` tile. Per design tile (Task 0), there may also be a "+ Smart Playlist" tile that calls `onCreateSmartPlaylist`. Long-press a Smart Playlist tile triggers a delete confirmation dialog using the existing `ConfirmDialog`.

**Detail screen:**

Header (back button + name + matched count + edit button), body (matched-episode list using the existing `EpisodeRow`-style row from the podcast detail screen — borrow the exact row composable; do not create a new one). Tap row → `Route.EpisodeDetail`. Empty state: "No matching episodes yet — try adjusting the predicate."

- [ ] **Steps 1-6:** Implement detail VM + screen, tile, library wiring, navigation, paywall, in TDD order. The detail VM is small enough that one combined commonTest covers it (Flow emits matched ID list given a fake resolver).

- [ ] **Step 7: Compile + tests green** — full sequence (compile + ktlint + detekt + tests + iOS).

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/playlists/ \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/ \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice7(playlists): Library virtual rows + detail screen + Pro-gated nav"
```

---

### Task 11: Final green-check + emulator verification + close-out

- [ ] **Step 1: Full green-check sequence**

```bash
./gradlew :composeApp:ktlintFormat
./gradlew :composeApp:compileFossDebugKotlinAndroid
./gradlew :composeApp:detekt
./gradlew :composeApp:testFossDebugUnitTest
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:verifyPaparazziFossDebug
```

All exit 0. If any fail, fix before proceeding (per CLAUDE.md "All tests must pass before declaring work done").

- [ ] **Step 2: Emulator walkthrough on Pixel_9a**

Install: `./gradlew :composeApp:installFossDebug`. Boot emulator, then verify the following user flow with `adb shell uiautomator dump`:

1. Launch app → Library → see existing list tiles + (if any data) playlists area appears with "+ Smart Playlist" affordance.
2. Tap "+ Smart Playlist" → editor opens (Pro flavor auto-unlocks).
3. Enter name "Recent unplayed". Toggle State chip → Unplayed. Toggle Age chip → 7 days. Live preview ticks to a non-zero count.
4. Tap Save → return to Library → new tile appears with "Recent unplayed" + episode count.
5. Tap the tile → detail screen opens with matched episodes. Tap a row → episode detail opens.
6. Back to Library → long-press tile → confirm dialog → Delete → tile disappears.
7. Edit-mode entry: create a new playlist, save, then re-tap → editor opens pre-filled.

Capture two screenshots: `/tmp/kofipod-slice7-editor.png` (editor with chips toggled) and `/tmp/kofipod-slice7-library.png` (Library with playlist tile rendered).

- [ ] **Step 3: Update RALPH_STATUS.md**

- Bump "Current slice: 7 of 10" → "8 of 10".
- Mark `[ ] **7**` → `[x] **7**` with verification date.
- Append iteration entries summarising tasks 0–11.
- Append plan-defect entries to "Plan-defect log" (whatever tripped during execution).
- Update "Slice 7 task progress" note in Notes section.

- [ ] **Step 4: Close-out commit**

```bash
git add RALPH_STATUS.md
git commit -m "slice7(playlists): close out — Smart Playlists verified on Pixel_9a"
```

> **Per memory rule "Kofipod Pro slices stay isolated":** do NOT push, do NOT merge to master. The branch (`worktree-kofipodpro-pre0`) stays local until the full Pro feature set is tested.

---

## Summary

12 tasks. TDD-heavy at the data + evaluator core (Tasks 2–7). UI weight in Tasks 9–10. Schema bump confined to Task 1. The `interface + Impl` shape (locked-in convention from Slices 4 + 6) is used for `EpisodeFactsRepository` and `SmartPlaylistRepository` from the start to avoid the recurring expect/actual constructor-mismatch trap that bit Tasks 4 / 8 / 11 of Slice 6 — even though Slice 7 has no platform-specific actuals planned, the consistency makes test fakes trivial.

**Recurring KMP defects to actively avoid (per Slice-6 plan-defect log):**
- `runCatching { ... }` swallows `CancellationException`. If any code in this slice uses `runCatching` (likely in the editor's "validate name" path or the VM's save flow), explicitly re-throw `CancellationException` before the generic handler.
- Tests that need `JdbcSqliteDriver` go in `androidUnitTest` (JVM-only), NOT `commonTest`.
- `Dispatchers.IO` is JVM-only — use `Dispatchers.Default` in commonMain.
- ViewModels that load via Koin's `viewModel { }` factory must extend `: ViewModel()`.
- Add new platform-specific constructor params via `interface X` + `expect class XImpl : X`, never `expect open class X`.
