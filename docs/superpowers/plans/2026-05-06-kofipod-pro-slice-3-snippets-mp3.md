# Kofipod Pro Slice 3 — Snippets MVP (MP3-only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the MP3-only Snippets MVP — Player Snip button (Pro-gated) opens a full-screen editor that lets the user trim a 60s draft, persists the snippet, and renders + shares an MP3 via a foreground service. Proves the foreground-service render pattern before Slice 4 takes on Media3 Transformer's MP4 risk.

**Architecture:** New `app.kofipod.snippets` package owning domain types + `SnippetRepository` + `SnippetComposerViewModel` + `SnippetExporter` (expect/actual; Android = Media3 Transformer audio-only export, iOS = stub). New `SnippetRenderService` foreground service (`mediaProcessing` FG type, API 34+, with `dataSync` fallback for older targets). New SQLDelight `Snippet` table at schema 18. Existing `Sharer` extended with `shareFile(path, mimeType, title)` for cross-MIME share. `Route.SnippetEditor(snippetId)` added; Player Snip button wires through `PaywallRouter` + the same Pro-gate pattern as Bookmarks (Slice 1) and Library Search (Slice 2). Episode Detail "Saved" section gains snippet rows alongside bookmarks. No waveform / no caption pipeline / no AirPod-tap in this slice — those land in Slice 4 alongside MP4.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight 2.0.2 + sqlite-3-24-dialect, Koin, Media3 Transformer (Android-only, audio-only path for MP3), kotlinx.coroutines.

---

## Scope discipline

This slice is intentionally narrow. **Out of scope** (and explicitly deferred):

- MP4 render path / Transformer composition graph / cover bg / waveform overlay / caption overlay → **Slice 4**.
- AirPod / wired-headset double-tap mapped through MediaSession custom command → **Slice 4** (needs MP4-or-MP3 user choice, which the editor will handle once both formats exist).
- Caption pipeline (publisher transcript → Gemini fallback → none) → **Slice 4**. MP3 ID3 tags ship as title + comment in this slice; no per-frame karaoke.
- Long-press on the playback timeline as a Snip trigger → **Slice 4**. Player Snip icon button is the only trigger this slice.
- Waveform with start/end drag handles → **Slice 4**. The editor in this slice uses numeric mm:ss.S start/end fields with ±1s / ±5s buttons (the spec leaves visual treatment to "primitive components ... once visual treatment lands from Claude Design", and we don't have that asset yet).
- "Search by snippet title / captionOverride" surfacing through `LibrarySearchIndex` → **Slice 4** (caption text and snippet title are added to FTS only after captions exist).
- Backup rules update for the Snippet table → **Slice 5**, alongside `PkmConnection` / `ExportLog` (the spec batches these together at line 344).

Anything not on this list is **in** scope for this slice.

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq` | Schema definition + named queries (insert / update fields / delete / selectByEpisode / selectAllWithContext / selectByPodcastWithContext / selectById). |
| `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/18.sqm` | Apply `Snippet` table to existing v17 databases. Nothing to backfill (table is new). |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt` | Plain `data class Snippet`, `data class SnippetWithContext`, `enum class SnippetFormat { MP3 }` (MP4 added in Slice 4). |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetWindow.kt` | Pure timestamp-math helpers (`computeLast60sWindow`, `clampWindow`, `formatTimestampDeci`). DRYs the editor and "Snip last 60s" code paths. Unit tested. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRepository.kt` | Owner of the Snippet table. CRUD + `createDraftFromPlayer(...)` (the "snip last 60s" entry point), `observeForEpisode` / `observeAll` / `observeForPodcast` Flows, `setRendered(id, format, path)` callback for the render service. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt` | `expect class SnippetExporter` with one suspend method: `suspend fun exportMp3(snippet: Snippet, sourceUrl: String, outputPath: String, onProgress: (Float) -> Unit): Result<String>`. Returns the absolute output path on success, error on failure. iOS actual ships as `TODO("iOS not yet supported")`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt` | Android actual — wraps Media3 Transformer with a `Composition` containing a single `EditedMediaItem` whose `clippingConfiguration` is set to `[startMs, endMs]` and whose video track is **removed** (`removeVideo = true`). Output is `.mp3` (Transformer infers MIME from filename → `audio/mpeg`). Uses `Transformer.Listener` + a `CompletableDeferred<Result<String>>` to bridge the listener callback into a suspend point. |
| `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt` | iOS actual stub: throws `NotImplementedError("Snippets not yet supported on iOS")`. Compile-only; iOS isn't a focus per CLAUDE.md. |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt` | Foreground service (`mediaProcessing` FG type on API 34+, falls back to `dataSync` since the manifest already declares it). Receives an EXTRA_SNIPPET_ID, looks up the snippet, finds source audio (downloaded path → enclosure URL fallback), invokes `SnippetExporter.exportMp3(...)`, writes back via `repo.setRendered(...)`, posts a "render complete" notification with a tap intent that fires the system share sheet, then `stopSelf()`. Renders one snippet at a time (queued requests stack up via START_REDELIVER_INTENT). |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderBroadcaster.kt` | Tiny helper that `Context.startForegroundService(intent)` on behalf of common code (mirrors `DownloadBroadcaster`). Bridges the common ViewModel/Repository to the Android-only service launch. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt` | `expect class SnippetRenderLauncher { fun enqueue(snippetId: String) }`. Android actual delegates to `SnippetRenderBroadcaster`; iOS actual is a no-op. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorScreen.kt` | Full-screen editor. Title field, start mm:ss.S field, end mm:ss.S field, ±1s / ±5s buttons on each, format chip (MP3 only — disabled MP4 chip with "Coming soon" label, per spec line 263 the editor exposes the format toggle even when one option is unavailable), "Render & Share" primary CTA, Cancel back button. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorViewModel.kt` | Holds `StateFlow<SnippetEditorUiState>`. `loadSnippet(id)` populates from repo. `setStart` / `setEnd` / `setTitle` mutate the in-memory draft. `save()` persists. `renderAndShare()` calls `repo.save()` + `launcher.enqueue(id)`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSourceResolver.kt` | Pure-Kotlin helper: given an `Episode`, returns either `Local(path)` (if `Download.localPath` is non-blank and the file exists per the platform `FileChecker`) or `Remote(url)` (the enclosure URL). The `FileChecker` boundary keeps this commonMain-testable. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/FileChecker.kt` | `expect class FileChecker { fun exists(path: String): Boolean }`. Android actual = `File(path).exists()`. iOS actual = `false` (no Snippets on iOS). |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/FileChecker.android.kt` | Android actual. |
| `composeApp/src/iosMain/kotlin/app/kofipod/snippets/FileChecker.ios.kt` | iOS actual stub. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetWindowTest.kt` | Unit tests for `computeLast60sWindow` + `clampWindow` + `formatTimestampDeci`. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetRepositoryTest.kt` | Unit tests for repo CRUD and `createDraftFromPlayer` (in-memory JdbcSqliteDriver). |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSourceResolverTest.kt` | Unit tests with a fake `FileChecker`. |

### Modified files

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt` | Bump `DB_SCHEMA_VERSION` from `17` to `18`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt` | Add `@Serializable data class SnippetEditor(val snippetId: String) : Route`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt` | Wire `composable<Route.SnippetEditor>` to `SnippetEditorScreen`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/share/Sharer.kt` | Add `expect fun shareFile(title: String, path: String, mimeType: String)`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/share/Sharer.android.kt` | Implement `shareFile` via `FileProvider.getUriForFile` + `ACTION_SEND` with the supplied MIME type. |
| `composeApp/src/iosMain/kotlin/app/kofipod/share/Sharer.ios.kt` | Add iOS actual `shareFile` stub (no-op TODO). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt` | Add `onSnipTapped(): Unit` Pro-gate (mirrors `onBookmarkTapped`). On Pro: build draft via `repo.createDraftFromPlayer(...)`, then route to `SnippetEditor`. On Free/Unknown: `paywallRouter.requestPaywall("paywall_snippet")`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt` | Pass `onSnipTapped` into `PlayerTopBar`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt` | Add a Snip icon button next to the existing Bookmark icon button. Both unconditionally visible (per spec line 161 — Free users still see the buttons; the tap opens Paywall). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPIcon.kt` | Add `KPIconName.Scissors` (or whatever fits the existing naming convention — confirm by reading existing names) for the Snip button glyph. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt` | Inject `SnippetRepository`. Combine with bookmark Flow so the "Saved" section emits both kinds (sealed `SavedItem.BookmarkItem` / `SavedItem.SnippetItem`). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` (or wherever the Saved section is rendered today) | Render snippet rows alongside bookmark rows in the Saved section. Tap on snippet row → navigate to `Route.SnippetEditor(snippetId)`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` | Register `single { SnippetRepository(get()) }`, `single { SnippetSourceResolver(get()) }`, `single { FileChecker() }`, `single { SnippetRenderLauncher(...) }`. Add `viewModel { SnippetEditorViewModel(...) }`. Bump `PlayerViewModel` factory to take `snippets: SnippetRepository` + `snippetLauncher: SnippetRenderLauncher`. Bump `EpisodeDetailViewModel` factory to take `snippets: SnippetRepository`. |
| `composeApp/src/androidMain/AndroidManifest.xml` | Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"/>` (API 34+ marks this required when `foregroundServiceType="mediaProcessing"`). Register `<service android:name=".snippets.SnippetRenderService" android:foregroundServiceType="mediaProcessing\|dataSync" android:exported="false"/>` — using both bits via the `\|` syntax keeps API 31–33 emulators on `dataSync` and API 34+ emulators on `mediaProcessing`. |
| `composeApp/src/androidMain/res/xml/file_paths.xml` | Add `<cache-path name="snippets" path="snippets/"/>` so `FileProvider` can vend share URIs for files written under `cacheDir/snippets/`. |
| `composeApp/build.gradle.kts` | Add Media3 Transformer dependency to `androidMain` (`androidx.media3:media3-transformer:<aligned with existing media3 version>`) + `androidx.media3:media3-effect` if the Composition graph needs it (audio-only path probably doesn't, but verify). |
| `gradle/libs.versions.toml` | Add `media3-transformer` library entry. |
| `config/detekt/detekt.yml` | (No change.) `androidx.media3.*` is already on the `ForbiddenImport` list — Transformer imports are confined to androidMain anyway. Confirm during implementation. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt` | (No change in this slice.) Note: a future "Snippets" entry-point on Library is not in scope; bookmarks-list-style aggregation for snippets is deferred to Slice 4 alongside MP4. |

---

## Tasks

Each task ends with a `git commit -m`. Don't squash. The CLAUDE.md pre-commit hook runs `ktlintFormat` + `detekt` on staged Kotlin — let it run; if it fails, fix and create a NEW commit.

---

### Task 1: Snippet table + migration to schema 18

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/18.sqm`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt`

- [ ] **Step 1: Write `Snippet.sq` exactly as below.**

```sql
-- Slice 3 (Pro Snippets MVP, MP3-only): per-episode snippet draft + render
-- bookkeeping. Cascades from both Episode and Podcast (the redundant
-- podcastId column matches the Bookmark pattern from Slice 1, so per-podcast
-- snippet aggregation can render without a JOIN through Episode).
--
-- lastExportFormat / lastExportPath are nullable: NULL means the snippet has
-- never been rendered (it's a draft). Re-rendering with a different trim or
-- format overwrites these and replaces the file at lastExportPath.
CREATE TABLE Snippet (
    id                TEXT NOT NULL PRIMARY KEY,
    episodeId         TEXT NOT NULL,
    podcastId         TEXT NOT NULL,
    startMs           INTEGER NOT NULL,
    endMs             INTEGER NOT NULL,
    title             TEXT,
    captionOverride   TEXT,
    createdAtMs       INTEGER NOT NULL,
    lastExportFormat  TEXT,
    lastExportPath    TEXT,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE,
    FOREIGN KEY (podcastId) REFERENCES Podcast(id) ON DELETE CASCADE
);

CREATE INDEX snippet_byEpisode  ON Snippet(episodeId, startMs);
CREATE INDEX snippet_byPodcast  ON Snippet(podcastId, createdAtMs);
CREATE INDEX snippet_byCreated  ON Snippet(createdAtMs);

insert:
INSERT INTO Snippet (id, episodeId, podcastId, startMs, endMs, title, captionOverride, createdAtMs, lastExportFormat, lastExportPath)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

updateTrim:
UPDATE Snippet SET startMs = ?, endMs = ? WHERE id = ?;

updateTitle:
UPDATE Snippet SET title = ? WHERE id = ?;

updateCaptionOverride:
UPDATE Snippet SET captionOverride = ? WHERE id = ?;

setRendered:
UPDATE Snippet SET lastExportFormat = ?, lastExportPath = ? WHERE id = ?;

deleteById:
DELETE FROM Snippet WHERE id = ?;

selectById:
SELECT * FROM Snippet WHERE id = ?;

selectByEpisode:
SELECT * FROM Snippet WHERE episodeId = ? ORDER BY startMs ASC;

selectAllWithContext:
SELECT
    s.id               AS id,
    s.episodeId        AS episodeId,
    s.podcastId        AS podcastId,
    s.startMs          AS startMs,
    s.endMs            AS endMs,
    s.title            AS title,
    s.captionOverride  AS captionOverride,
    s.createdAtMs      AS createdAtMs,
    s.lastExportFormat AS lastExportFormat,
    s.lastExportPath   AS lastExportPath,
    e.title            AS episodeTitle,
    p.title            AS podcastTitle,
    p.artworkUrl       AS artworkUrl
FROM Snippet s
INNER JOIN Episode e ON e.id = s.episodeId
INNER JOIN Podcast p ON p.id = s.podcastId
ORDER BY s.createdAtMs DESC;

selectByPodcastWithContext:
SELECT
    s.id               AS id,
    s.episodeId        AS episodeId,
    s.podcastId        AS podcastId,
    s.startMs          AS startMs,
    s.endMs            AS endMs,
    s.title            AS title,
    s.captionOverride  AS captionOverride,
    s.createdAtMs      AS createdAtMs,
    s.lastExportFormat AS lastExportFormat,
    s.lastExportPath   AS lastExportPath,
    e.title            AS episodeTitle,
    p.title            AS podcastTitle,
    p.artworkUrl       AS artworkUrl
FROM Snippet s
INNER JOIN Episode e ON e.id = s.episodeId
INNER JOIN Podcast p ON p.id = s.podcastId
WHERE s.podcastId = ?
ORDER BY s.createdAtMs DESC;

countByEpisode:
SELECT COUNT(*) AS c FROM Snippet WHERE episodeId = ?;
```

- [ ] **Step 2: Write `migrations/18.sqm` exactly as below.**

```sql
-- Slice 3 (Pro Snippets MVP): introduce Snippet table for per-episode clip
-- drafts. No backfill — table is brand new. Bookmark and TranscriptCache
-- from earlier slices are untouched.
CREATE TABLE Snippet (
    id                TEXT NOT NULL PRIMARY KEY,
    episodeId         TEXT NOT NULL,
    podcastId         TEXT NOT NULL,
    startMs           INTEGER NOT NULL,
    endMs             INTEGER NOT NULL,
    title             TEXT,
    captionOverride   TEXT,
    createdAtMs       INTEGER NOT NULL,
    lastExportFormat  TEXT,
    lastExportPath    TEXT,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE,
    FOREIGN KEY (podcastId) REFERENCES Podcast(id) ON DELETE CASCADE
);

CREATE INDEX snippet_byEpisode  ON Snippet(episodeId, startMs);
CREATE INDEX snippet_byPodcast  ON Snippet(podcastId, createdAtMs);
CREATE INDEX snippet_byCreated  ON Snippet(createdAtMs);
```

- [ ] **Step 3: Bump schema version constant.**

In `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt`:

```kotlin
const val DB_SCHEMA_VERSION = 18
```

(was `17`).

- [ ] **Step 4: Compile-only check (Android + iOS).**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL on both. SQLDelight regenerates `SnippetQueries`. If the iOS compile fails, the failure is **not** Snippet.sq itself (it's commonMain SQLDelight) — investigate before continuing.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/18.sqm \
        composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt
git commit -m "slice3(snippets): Snippet table + migration 17→18"
```

---

### Task 2: Snippet domain types + window math (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetWindow.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetWindowTest.kt`

- [ ] **Step 1: Write the failing tests first.**

`composeApp/src/test/kotlin/app/kofipod/snippets/SnippetWindowTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetWindowTest {

    @Test
    fun `last 60s window from mid episode returns previous 60 seconds`() {
        val w = SnippetWindow.computeLast60sWindow(positionMs = 120_000L, durationMs = 600_000L)
        assertEquals(60_000L, w.startMs)
        assertEquals(120_000L, w.endMs)
    }

    @Test
    fun `last 60s window early in episode clamps start to zero`() {
        val w = SnippetWindow.computeLast60sWindow(positionMs = 30_000L, durationMs = 600_000L)
        assertEquals(0L, w.startMs)
        assertEquals(30_000L, w.endMs)
    }

    @Test
    fun `last 60s window at episode start yields 1ms zero-length-safe window`() {
        // Position 0 + duration 0 should still yield a non-negative span.
        val w = SnippetWindow.computeLast60sWindow(positionMs = 0L, durationMs = 0L)
        assertEquals(0L, w.startMs)
        assertEquals(0L, w.endMs)
    }

    @Test
    fun `clamp pulls negative start up to zero`() {
        val w = SnippetWindow.clampWindow(startMs = -500L, endMs = 5_000L, durationMs = 600_000L)
        assertEquals(0L, w.startMs)
        assertEquals(5_000L, w.endMs)
    }

    @Test
    fun `clamp pulls past-duration end down to duration`() {
        val w = SnippetWindow.clampWindow(startMs = 100_000L, endMs = 700_000L, durationMs = 600_000L)
        assertEquals(100_000L, w.startMs)
        assertEquals(600_000L, w.endMs)
    }

    @Test
    fun `clamp swaps reversed start and end`() {
        val w = SnippetWindow.clampWindow(startMs = 80_000L, endMs = 20_000L, durationMs = 600_000L)
        assertEquals(20_000L, w.startMs)
        assertEquals(80_000L, w.endMs)
    }

    @Test
    fun `clamp enforces minimum 1 second span by extending end`() {
        // 500ms span isn't renderable. Extend end to start + 1000ms.
        val w = SnippetWindow.clampWindow(startMs = 10_000L, endMs = 10_500L, durationMs = 600_000L)
        assertEquals(10_000L, w.startMs)
        assertEquals(11_000L, w.endMs)
    }

    @Test
    fun `clamp prefers extending end but falls back to pulling start when at duration`() {
        // start = end = duration. Can't extend end (already at duration), so pull start back.
        val w = SnippetWindow.clampWindow(startMs = 600_000L, endMs = 600_000L, durationMs = 600_000L)
        assertEquals(599_000L, w.startMs)
        assertEquals(600_000L, w.endMs)
    }

    @Test
    fun `formatTimestampDeci formats sub-second precision`() {
        assertEquals("00:00.0", SnippetWindow.formatTimestampDeci(0L))
        assertEquals("00:00.5", SnippetWindow.formatTimestampDeci(500L))
        assertEquals("00:01.2", SnippetWindow.formatTimestampDeci(1_234L))
        assertEquals("01:30.0", SnippetWindow.formatTimestampDeci(90_000L))
        assertEquals("12:34.5", SnippetWindow.formatTimestampDeci((12 * 60 + 34) * 1000L + 500L))
        assertEquals("60:00.0", SnippetWindow.formatTimestampDeci(60 * 60 * 1000L))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetWindowTest"`
Expected: 9 tests, all FAIL with unresolved-reference (`SnippetWindow` not yet defined).

- [ ] **Step 3: Implement `Snippet.kt` (domain types).**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * MVP supports MP3 only. MP4 ships in Slice 4 alongside the Media3 Transformer
 * video composition graph. The enum is introduced now so the editor's format
 * chip and the Snippet.lastExportFormat column don't need to change wire shape
 * later — Slice 4 will simply add `MP4` and start emitting it.
 */
enum class SnippetFormat(val wire: String, val mimeType: String, val fileExtension: String) {
    MP3(wire = "mp3", mimeType = "audio/mpeg", fileExtension = "mp3"),
    ;

    companion object {
        fun fromWire(value: String?): SnippetFormat? = entries.firstOrNull { it.wire == value }
    }
}

data class Snippet(
    val id: String,
    val episodeId: String,
    val podcastId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String?,
    val captionOverride: String?,
    val createdAtMs: Long,
    val lastExportFormat: SnippetFormat?,
    val lastExportPath: String?,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val isRendered: Boolean get() = lastExportFormat != null && !lastExportPath.isNullOrBlank()
}

data class SnippetWithContext(
    val snippet: Snippet,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String,
)
```

- [ ] **Step 4: Implement `SnippetWindow.kt` (window math).**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

object SnippetWindow {
    private const val LAST_WINDOW_MS = 60_000L
    private const val MIN_SPAN_MS = 1_000L

    data class Window(val startMs: Long, val endMs: Long)

    /**
     * Per spec § F1: Snip-last-60s opens an editor with a draft anchored at
     * `[currentPosition − 60_000ms, currentPosition]`. Clamps start to zero
     * for early-position episodes; never overruns duration.
     */
    fun computeLast60sWindow(positionMs: Long, durationMs: Long): Window {
        val end = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val start = (end - LAST_WINDOW_MS).coerceAtLeast(0L)
        return Window(start, end)
    }

    /**
     * Bring an arbitrary user-edited window back into [0, duration] with a
     * minimum 1s span. Swaps reversed start/end. Prefers extending end to
     * satisfy the min-span; falls back to pulling start back if end is
     * already at duration.
     */
    fun clampWindow(startMs: Long, endMs: Long, durationMs: Long): Window {
        val cap = durationMs.coerceAtLeast(0L)
        var s = startMs.coerceIn(0L, cap)
        var e = endMs.coerceIn(0L, cap)
        if (e < s) { val t = s; s = e; e = t }
        if (e - s < MIN_SPAN_MS) {
            val needed = MIN_SPAN_MS - (e - s)
            val canExtendEnd = (cap - e).coerceAtLeast(0L)
            if (canExtendEnd >= needed) {
                e += needed
            } else {
                e = cap
                s = (e - MIN_SPAN_MS).coerceAtLeast(0L)
            }
        }
        return Window(s, e)
    }

    /** mm:ss.s formatting (one decimal). For UI display only — not for storage. */
    fun formatTimestampDeci(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalDeci = (safe + 50L) / 100L  // round to nearest 0.1s
        val deci = (totalDeci % 10L).toInt()
        val totalSec = totalDeci / 10L
        val mm = (totalSec / 60L).toInt()
        val ss = (totalSec % 60L).toInt()
        val mmStr = if (mm < 10) "0$mm" else mm.toString()
        val ssStr = if (ss < 10) "0$ss" else ss.toString()
        return "$mmStr:$ssStr.$deci"
    }
}
```

- [ ] **Step 5: Run tests to verify they pass.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetWindowTest"`
Expected: 9 PASS.

- [ ] **Step 6: iOS compile sanity check.**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetWindow.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetWindowTest.kt
git commit -m "slice3(snippets): domain types + window math (clamp/format)"
```

---

### Task 3: SnippetRepository (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRepository.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests first.**

`SnippetRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: KofipodDatabase
    private lateinit var repo: SnippetRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        db = KofipodDatabase(driver)
        repo = SnippetRepository(db)
        // Seed a podcast + episode so FKs are satisfied.
        db.podcastQueries.insert(
            id = "p1", title = "Pod", author = "Auth", description = "",
            artworkUrl = "", feedUrl = "https://x/y.xml", listId = null,
            autoDownloadEnabled = 0, notifyNewEpisodesEnabled = 1,
            lastCheckedAt = null, addedAt = 1L, primaryCategory = "Test",
        )
        db.episodeQueries.insert(
            id = "e1", podcastId = "p1", guid = "e1g", title = "Ep1",
            description = "", publishedAt = 1L, durationSec = 600,
            enclosureUrl = "https://x/e1.mp3", enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L, seasonNumber = null, episodeNumber = 1,
            imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )
    }

    @AfterTest fun tearDown() = driver.close()

    @Test
    fun `createDraftFromPlayer persists a draft with last-60s window`() = runTest {
        val id = repo.createDraftFromPlayer(
            episodeId = "e1", podcastId = "p1",
            playerPositionMs = 120_000L, episodeDurationMs = 600_000L,
            episodeTitle = "Ep1", nowMs = 1_700_000_000L,
        )
        val s = repo.observeForEpisode("e1").first().single()
        assertEquals(id, s.id)
        assertEquals(60_000L, s.startMs)
        assertEquals(120_000L, s.endMs)
        assertEquals("Ep1 — 01:00.0", s.title) // default title format
        assertNull(s.lastExportFormat)
        assertNull(s.lastExportPath)
    }

    @Test
    fun `createDraftFromPlayer clamps start to zero when position is below 60s`() = runTest {
        val id = repo.createDraftFromPlayer(
            episodeId = "e1", podcastId = "p1",
            playerPositionMs = 30_000L, episodeDurationMs = 600_000L,
            episodeTitle = "Ep1", nowMs = 1L,
        )
        val s = repo.observeForEpisode("e1").first().single { it.id == id }
        assertEquals(0L, s.startMs)
        assertEquals(30_000L, s.endMs)
    }

    @Test
    fun `updateTrim mutates startMs and endMs`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        repo.updateTrim(id, 70_000L, 100_000L)
        val s = repo.observeForEpisode("e1").first().single { it.id == id }
        assertEquals(70_000L, s.startMs)
        assertEquals(100_000L, s.endMs)
    }

    @Test
    fun `updateTrim clamps invalid input via SnippetWindow rules`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        repo.updateTrim(id, -500L, 700_000L, durationMs = 600_000L)
        val s = repo.observeForEpisode("e1").first().single { it.id == id }
        assertEquals(0L, s.startMs)
        assertEquals(600_000L, s.endMs)
    }

    @Test
    fun `setRendered records format and path`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        repo.setRendered(id, SnippetFormat.MP3, "/data/cache/snippets/$id.mp3")
        val s = repo.observeForEpisode("e1").first().single { it.id == id }
        assertEquals(SnippetFormat.MP3, s.lastExportFormat)
        assertEquals("/data/cache/snippets/$id.mp3", s.lastExportPath)
        assertTrue(s.isRendered)
    }

    @Test
    fun `deleteById removes the row`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        repo.deleteById(id)
        assertEquals(emptyList(), repo.observeForEpisode("e1").first())
    }

    @Test
    fun `selectById returns null for missing row`() = runTest {
        assertNull(repo.selectById("nope"))
    }

    @Test
    fun `selectById returns the row when present`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        val s = repo.selectById(id)
        assertNotNull(s)
        assertEquals(id, s.id)
    }

    @Test
    fun `observeAllWithContext joins episode and podcast metadata`() = runTest {
        val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        val all = repo.observeAllWithContext().first()
        assertEquals(1, all.size)
        assertEquals(id, all.single().snippet.id)
        assertEquals("Ep1", all.single().episodeTitle)
        assertEquals("Pod", all.single().podcastTitle)
    }

    @Test
    fun `episode delete cascades to snippets`() = runTest {
        repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        db.episodeQueries.deleteById("e1")
        assertEquals(emptyList(), repo.observeForEpisode("e1").first())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetRepositoryTest"`
Expected: All FAIL — `SnippetRepository` not yet defined.

- [ ] **Step 3: Implement `SnippetRepository.kt`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.random.Random

class SnippetRepository(private val db: KofipodDatabase) {

    /**
     * Create a "snip last 60s" draft from current player state. Returns the
     * generated id. Title defaults to `"<episode title> — <mm:ss.s start>"`
     * for fast user identification; user can edit in the editor.
     *
     * Caller is responsible for ensuring [episodeId] / [podcastId] exist (FK).
     */
    fun createDraftFromPlayer(
        episodeId: String,
        podcastId: String,
        playerPositionMs: Long,
        episodeDurationMs: Long,
        episodeTitle: String,
        nowMs: Long,
    ): String {
        val window = SnippetWindow.computeLast60sWindow(playerPositionMs, episodeDurationMs)
        val id = generateId(nowMs)
        val defaultTitle = "$episodeTitle — ${SnippetWindow.formatTimestampDeci(window.startMs)}"
        db.snippetQueries.insert(
            id = id,
            episodeId = episodeId,
            podcastId = podcastId,
            startMs = window.startMs,
            endMs = window.endMs,
            title = defaultTitle,
            captionOverride = null,
            createdAtMs = nowMs,
            lastExportFormat = null,
            lastExportPath = null,
        )
        return id
    }

    /**
     * Update trim with optional clamping. If [durationMs] is supplied, the
     * pair is run through [SnippetWindow.clampWindow] before write — the
     * editor calls this overload. The unclamped overload is for tests and
     * for callers that have already validated the pair.
     */
    fun updateTrim(id: String, startMs: Long, endMs: Long, durationMs: Long) {
        val w = SnippetWindow.clampWindow(startMs, endMs, durationMs)
        db.snippetQueries.updateTrim(w.startMs, w.endMs, id)
    }

    fun updateTrim(id: String, startMs: Long, endMs: Long) {
        db.snippetQueries.updateTrim(startMs, endMs, id)
    }

    fun updateTitle(id: String, title: String?) =
        db.snippetQueries.updateTitle(title?.takeIf { it.isNotBlank() }, id)

    fun updateCaptionOverride(id: String, captionOverride: String?) =
        db.snippetQueries.updateCaptionOverride(captionOverride?.takeIf { it.isNotBlank() }, id)

    fun setRendered(id: String, format: SnippetFormat, path: String) =
        db.snippetQueries.setRendered(format.wire, path, id)

    fun deleteById(id: String) = db.snippetQueries.deleteById(id)

    suspend fun selectById(id: String): Snippet? =
        db.snippetQueries.selectById(id).asFlow().mapToOneOrNull(Dispatchers.Default).first()
            ?.let(::toDomain)

    fun observeForEpisode(episodeId: String): Flow<List<Snippet>> =
        db.snippetQueries.selectByEpisode(episodeId).asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map(::toDomain) }
            .flowOn(Dispatchers.Default)

    fun observeAllWithContext(): Flow<List<SnippetWithContext>> =
        db.snippetQueries.selectAllWithContext().asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    SnippetWithContext(
                        snippet = Snippet(
                            id = row.id,
                            episodeId = row.episodeId,
                            podcastId = row.podcastId,
                            startMs = row.startMs,
                            endMs = row.endMs,
                            title = row.title,
                            captionOverride = row.captionOverride,
                            createdAtMs = row.createdAtMs,
                            lastExportFormat = SnippetFormat.fromWire(row.lastExportFormat),
                            lastExportPath = row.lastExportPath,
                        ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun toDomain(row: app.kofipod.db.Snippet): Snippet = Snippet(
        id = row.id,
        episodeId = row.episodeId,
        podcastId = row.podcastId,
        startMs = row.startMs,
        endMs = row.endMs,
        title = row.title,
        captionOverride = row.captionOverride,
        createdAtMs = row.createdAtMs,
        lastExportFormat = SnippetFormat.fromWire(row.lastExportFormat),
        lastExportPath = row.lastExportPath,
    )

    private fun generateId(nowMs: Long): String {
        val rand = Random.nextLong(0L, Long.MAX_VALUE)
        return "snip-" + nowMs.toString(36) + "-" + rand.toString(36).takeLast(8)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetRepositoryTest"`
Expected: 10 PASS.

- [ ] **Step 5: iOS compile sanity check.**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRepository.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetRepositoryTest.kt
git commit -m "slice3(snippets): SnippetRepository CRUD + draft-from-player"
```

---

### Task 4: SnippetSourceResolver + FileChecker (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/FileChecker.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSourceResolver.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/FileChecker.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/snippets/FileChecker.ios.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSourceResolverTest.kt`

**Why this exists:** Render needs to feed Transformer a file path or URL. Downloaded episodes have a local path; un-downloaded ones must stream from the enclosure URL (Transformer accepts both). Resolution rule lives in pure-Kotlin `commonMain` so it's testable and slice-4-MP4 reuses it unchanged.

- [ ] **Step 1: Write the failing test first.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetSourceResolverTest {

    private class FakeFileChecker(private val existingPaths: Set<String>) : FileCheckerApi {
        override fun exists(path: String): Boolean = path in existingPaths
    }

    @Test
    fun `prefers local path when file exists`() {
        val r = SnippetSourceResolver(FakeFileChecker(setOf("/data/files/downloads/e1.mp3")))
        val src = r.resolve(
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
        val src = r.resolve(
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
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetSourceResolverTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `FileChecker.kt` (commonMain).**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Test-friendly seam over the platform's file existence check. Production
 * uses the [FileChecker] expect/actual; tests use [FakeFileChecker]
 * implementing the same interface.
 */
interface FileCheckerApi {
    fun exists(path: String): Boolean
}

expect class FileChecker() : FileCheckerApi
```

- [ ] **Step 4: Implement Android actual.**

`androidMain/kotlin/app/kofipod/snippets/FileChecker.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import java.io.File

actual class FileChecker actual constructor() : FileCheckerApi {
    actual override fun exists(path: String): Boolean =
        path.isNotBlank() && File(path).exists()
}
```

- [ ] **Step 5: Implement iOS stub.**

`iosMain/kotlin/app/kofipod/snippets/FileChecker.ios.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

actual class FileChecker actual constructor() : FileCheckerApi {
    actual override fun exists(path: String): Boolean = false
}
```

- [ ] **Step 6: Implement `SnippetSourceResolver.kt`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

sealed class SnippetSource {
    data class Local(val path: String) : SnippetSource()
    data class Remote(val url: String) : SnippetSource()
    data object None : SnippetSource()
}

class SnippetSourceResolver(private val fileChecker: FileCheckerApi) {
    fun resolve(localPath: String?, enclosureUrl: String): SnippetSource {
        if (!localPath.isNullOrBlank() && fileChecker.exists(localPath)) {
            return SnippetSource.Local(localPath)
        }
        if (enclosureUrl.isNotBlank()) {
            return SnippetSource.Remote(enclosureUrl)
        }
        return SnippetSource.None
    }
}
```

- [ ] **Step 7: Run tests + iOS compile.**

```
./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetSourceResolverTest"
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
Expected: 5 PASS + iOS BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/FileChecker.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSourceResolver.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/FileChecker.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/FileChecker.ios.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSourceResolverTest.kt
git commit -m "slice3(snippets): SnippetSourceResolver + FileChecker expect/actual"
```

---

### Task 5: SnippetExporter expect/actual (Android Media3 Transformer audio path)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Add Media3 Transformer dependency.**

In `gradle/libs.versions.toml`, under `[libraries]`, add (using the same `media3` version key already in use):

```toml
media3-transformer = { module = "androidx.media3:media3-transformer", version.ref = "media3" }
```

In `composeApp/build.gradle.kts`, in the `androidMain.dependencies { ... }` block, add:

```kotlin
implementation(libs.media3.transformer)
```

- [ ] **Step 2: Implement common `SnippetExporter.kt`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Renders a Snippet to a file at [outputPath]. Implementations are expected to:
 *  - support either a local file path or a streaming URL as [sourceUriOrPath]
 *  - clip exactly to [snippet.startMs, snippet.endMs]
 *  - emit format-appropriate output (MP3 → audio/mpeg ID3-tagged file)
 *  - report progress in [0f, 1f] via [onProgress]
 *  - return the absolute output path on success, or a Throwable on failure
 *
 * Slice 3 ships MP3 only. Slice 4 will add MP4 (Media3 Transformer with
 * Composition + BitmapOverlay + TextOverlay).
 */
expect class SnippetExporter {
    suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
```

- [ ] **Step 3: Implement iOS stub.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

actual class SnippetExporter {
    actual suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): Result<String> = Result.failure(NotImplementedError("Snippets not supported on iOS"))
}
```

- [ ] **Step 4: Implement Android actual using Media3 Transformer.**

`androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

actual class SnippetExporter(private val context: Context) {

    actual suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.Main) {
        // Transformer is built on the main thread (Android requirement).
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val mediaItem = MediaItem.Builder()
            .setUri(toUri(sourceUriOrPath))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(snippet.startMs)
                    .setEndPositionMs(snippet.endMs)
                    .build(),
            )
            .build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true) // audio-only
            .build()

        val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()

        val deferred = CompletableDeferred<Result<String>>()

        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC) // Transformer's MP3 encoder is the muxer's job
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, exportResult: ExportResult) {
                    deferred.complete(Result.success(outputFile.absolutePath))
                }

                override fun onError(c: Composition, exportResult: ExportResult, exportException: ExportException) {
                    deferred.complete(Result.failure(exportException))
                }
            })
            .build()

        // Progress polling — Transformer doesn't push progress; we poll via getProgress.
        // We don't want to block the calling coroutine on progress, so we just attach a
        // simple poller that runs while the deferred is pending.
        val pollerJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val holder = androidx.media3.transformer.ProgressHolder()
            while (!deferred.isCompleted) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                }
                kotlinx.coroutines.delay(250L)
            }
        }

        try {
            transformer.start(composition, outputPath)
            val result = deferred.await()
            pollerJob.cancel()
            result
        } catch (t: Throwable) {
            pollerJob.cancel()
            try { transformer.cancel() } catch (_: Throwable) {}
            Result.failure(t)
        }
    }

    private fun toUri(sourceUriOrPath: String): Uri =
        if (sourceUriOrPath.startsWith("http://") || sourceUriOrPath.startsWith("https://")) {
            Uri.parse(sourceUriOrPath)
        } else {
            Uri.fromFile(File(sourceUriOrPath))
        }
}
```

> **Implementer note:** the `setAudioMimeType(...)` line above pins the codec; the actual file extension `.mp3` is set by the caller via `outputPath`. Confirm Media3 Transformer's audio-only export defaults match this when running on the emulator. If Transformer rejects `.mp3` muxing on the bundled muxer, fall back to `.m4a` (`audio/mp4`) and update `SnippetFormat.MP3.fileExtension` + `mimeType` to match — record this as a deviation in the slice commit message.

- [ ] **Step 5: Compile both targets.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL on both. Detekt's `androidx.media3.*` ban applies only to commonMain, so the import is fine in androidMain.

- [ ] **Step 6: Commit.**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt
git commit -m "slice3(snippets): SnippetExporter expect/actual + Media3 Transformer audio path"
```

---

### Task 6: Sharer.shareFile + FileProvider wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/share/Sharer.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/share/Sharer.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/share/Sharer.ios.kt`
- Modify: `composeApp/src/androidMain/res/xml/file_paths.xml`

- [ ] **Step 1: Extend the common Sharer expect class.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.share

expect class Sharer {
    fun shareText(title: String, text: String)
    fun shareFile(title: String, path: String, mimeType: String, captionText: String? = null)
}
```

- [ ] **Step 2: Implement Android `shareFile`.**

In `androidMain/kotlin/app/kofipod/share/Sharer.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

actual class Sharer(private val context: Context) {
    actual fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    actual fun shareFile(title: String, path: String, mimeType: String, captionText: String?) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!captionText.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, captionText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
```

- [ ] **Step 3: Add iOS stub.**

In `composeApp/src/iosMain/kotlin/app/kofipod/share/Sharer.ios.kt` (find existing file or create):

```kotlin
actual fun shareFile(title: String, path: String, mimeType: String, captionText: String?) {
    // iOS: not implemented in this slice.
}
```

- [ ] **Step 4: Add `cache-path` entry to `file_paths.xml`.**

In `composeApp/src/androidMain/res/xml/file_paths.xml`, alongside existing entries (read first to see format), append:

```xml
<cache-path name="snippets" path="snippets/" />
```

- [ ] **Step 5: Compile both targets.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/share/Sharer.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/share/Sharer.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/share/Sharer.ios.kt \
        composeApp/src/androidMain/res/xml/file_paths.xml
git commit -m "slice3(snippets): Sharer.shareFile + FileProvider snippets cache-path"
```

---

### Task 7: SnippetRenderService (Android foreground service)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderBroadcaster.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.ios.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml`

- [ ] **Step 1: Create the common launcher seam.**

`commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Common-side handle that hands a snippetId off to the platform's render
 * pipeline. Android: starts SnippetRenderService as a foreground service.
 * iOS: no-op (Snippets are Android-only this milestone).
 */
expect class SnippetRenderLauncher {
    fun enqueue(snippetId: String)
}
```

- [ ] **Step 2: Android actual + broadcaster.**

`androidMain/kotlin/app/kofipod/snippets/SnippetRenderBroadcaster.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object SnippetRenderBroadcaster {
    fun enqueue(context: Context, snippetId: String) {
        val intent = Intent(context, SnippetRenderService::class.java).apply {
            putExtra(SnippetRenderService.EXTRA_SNIPPET_ID, snippetId)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
```

`androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context

actual class SnippetRenderLauncher(private val context: Context) {
    actual fun enqueue(snippetId: String) =
        SnippetRenderBroadcaster.enqueue(context, snippetId)
}
```

`iosMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.ios.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

actual class SnippetRenderLauncher {
    actual fun enqueue(snippetId: String) {
        // no-op
    }
}
```

- [ ] **Step 3: Implement `SnippetRenderService.kt`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import app.kofipod.R
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.share.Sharer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File

/**
 * One-shot foreground service that renders a single Snippet to disk and
 * triggers the system share sheet via the in-app Sharer when done. Multiple
 * concurrent enqueues stack up via START_REDELIVER_INTENT — the service runs
 * one render at a time to avoid Transformer concurrency surprises.
 *
 * FG type is `mediaProcessing` on API 34+ (matches the AndroidManifest entry);
 * older targets fall through to `dataSync` (already permitted).
 */
class SnippetRenderService : Service() {

    private val repo: SnippetRepository by inject()
    private val episodes: EpisodeSource by inject()
    private val downloads: DownloadRepository by inject()
    private val resolver: SnippetSourceResolver by inject()
    private val exporter: SnippetExporter by inject()
    private val sharer: Sharer by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snippetId = intent?.getStringExtra(EXTRA_SNIPPET_ID)
            ?: run { stopSelf(startId); return START_NOT_STICKY }

        startForegroundCompat(snippetId)
        currentJob = scope.launch {
            try {
                renderOne(snippetId)
            } finally {
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    private suspend fun renderOne(snippetId: String) {
        val snippet = repo.selectById(snippetId) ?: return
        val episode = episodes.fetchById(snippet.episodeId) ?: return

        val download = downloads.observeForEpisode(snippet.episodeId)
            // first emission is enough — we just need the current local path.
            .let { flow ->
                kotlinx.coroutines.flow.firstOrNull(flow)
            }
        val source = resolver.resolve(
            localPath = download?.localPath,
            enclosureUrl = episode.enclosureUrl,
        )
        val sourceUriOrPath = when (source) {
            is SnippetSource.Local -> source.path
            is SnippetSource.Remote -> source.url
            SnippetSource.None -> return
        }

        val outputDir = File(cacheDir, "snippets").apply { mkdirs() }
        val outputFile = File(outputDir, "${snippet.id}.${SnippetFormat.MP3.fileExtension}")

        val result = exporter.exportMp3(
            snippet = snippet,
            sourceUriOrPath = sourceUriOrPath,
            outputPath = outputFile.absolutePath,
            onProgress = { p -> updateProgressNotification(snippetId, p) },
        )

        result.fold(
            onSuccess = { path ->
                repo.setRendered(snippet.id, SnippetFormat.MP3, path)
                triggerShare(snippet, path)
            },
            onFailure = { /* TODO Slice 4: surface error toast via UiEventBus */ },
        )
    }

    private fun triggerShare(snippet: Snippet, path: String) {
        val episodeUrl = "https://podcastindex.org/podcast/${snippet.podcastId}?episode=${snippet.episodeId}"
        sharer.shareFile(
            title = snippet.title ?: "Snippet",
            path = path,
            mimeType = SnippetFormat.MP3.mimeType,
            captionText = "${snippet.title ?: "Snippet"}\n$episodeUrl",
        )
    }

    private fun startForegroundCompat(snippetId: String) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Snippet rendering",
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)

        val notif = buildProgressNotification(progress = 0f).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateProgressNotification(snippetId: String, progress: Float) {
        val notif = buildProgressNotification(progress).build()
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, notif)
    }

    private fun buildProgressNotification(progress: Float): NotificationCompat.Builder {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rendering snippet")
            .setContentText("$pct%")
            .setProgress(100, pct, /* indeterminate = */ progress <= 0f)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    override fun onDestroy() {
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SNIPPET_ID = "app.kofipod.extra.SNIPPET_ID"
        private const val CHANNEL_ID = "snippet_render"
        private const val NOTIF_ID = 0x517A1
    }
}
```

> **Implementer note:** The service uses Koin injection at the field level (`by inject()`) — confirm `KofipodApplication` already starts Koin globally. If not (and the existing `DownloadService` already uses Koin field injection), this slice picks up the same setup unchanged.

- [ ] **Step 4: Update AndroidManifest.**

In `composeApp/src/androidMain/AndroidManifest.xml`:

1. Add a permission alongside the existing FG permissions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
```

2. Register the service alongside the existing `DownloadService` entry:

```xml
<service
    android:name=".snippets.SnippetRenderService"
    android:foregroundServiceType="mediaProcessing|dataSync"
    android:exported="false" />
```

(The `mediaProcessing` token is API 34+; on API 31–33 the OS picks `dataSync` from the list.)

- [ ] **Step 5: Compile & smoke-build the APK.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:assembleDebug
```
Expected: BUILD SUCCESSFUL on all.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderBroadcaster.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.ios.kt \
        composeApp/src/androidMain/AndroidManifest.xml
git commit -m "slice3(snippets): foreground SnippetRenderService + render launcher seam"
```

---

### Task 8: Koin wiring + PlayerViewModel Snip gate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Modify (Android Koin module — find the file that registers Android-context-bound singletons): typically `composeApp/src/androidMain/kotlin/.../KoinAndroidModule.kt` or similar
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt`

- [ ] **Step 1: Discover where `Sharer(get())` is bound on Android (since it needs Context).**

Run: `grep -rn "Sharer(" composeApp/src/androidMain composeApp/src/commonMain | head`

The implementer will follow that pattern for `SnippetExporter(get())`, `FileChecker()`, and `SnippetRenderLauncher(get())` — `SnippetExporter` and `SnippetRenderLauncher` need an Android `Context`, so they live in the same Android-side Koin module as `Sharer`.

- [ ] **Step 2: Register common-side singletons in `CommonModule.kt`.**

Inside the `module { ... }` block, alongside existing repos:

```kotlin
single { app.kofipod.snippets.SnippetSourceResolver(get()) }
single { app.kofipod.snippets.SnippetRepository(get()) }
```

(`FileChecker`, `SnippetExporter`, and `SnippetRenderLauncher` are Android-context-bound — register them in the Android Koin module.)

In the `viewModel { ... }` block for `PlayerViewModel`, add new params:

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
        bookmarks = get(),
        snippets = get(),
        snippetLauncher = get(),
    )
}
```

Add the editor VM factory (will be implemented in Task 9):

```kotlin
viewModel { (snippetId: String) ->
    SnippetEditorViewModel(
        snippetId = snippetId,
        snippets = get(),
        launcher = get(),
    )
}
```

- [ ] **Step 3: Update PlayerViewModel.**

In `PlayerViewModel.kt`, add fields + the gate method, mirroring `onBookmarkTapped`:

```kotlin
class PlayerViewModel(
    // ...existing params...
    private val snippets: app.kofipod.snippets.SnippetRepository,
    private val snippetLauncher: app.kofipod.snippets.SnippetRenderLauncher,
) : ViewModel() {

    // ...

    private val _snippetEditorRoute = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snippetEditorRoute: SharedFlow<String> = _snippetEditorRoute

    /**
     * Pro-gated. On Pro: build a "snip last 60s" draft and emit a navigation
     * event for PlayerScreen to consume. On Free / Unknown: open paywall.
     */
    fun onSnipTapped() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> {
                val p = state.value.player
                val episodeId = p.episodeId ?: return
                if (p.podcastId.isBlank()) return
                viewModelScope.launch {
                    val id = snippets.createDraftFromPlayer(
                        episodeId = episodeId,
                        podcastId = p.podcastId,
                        playerPositionMs = p.positionMs,
                        episodeDurationMs = p.durationMs,
                        episodeTitle = p.title,
                        nowMs = Clock.System.now().toEpochMilliseconds(),
                    )
                    _snippetEditorRoute.tryEmit(id)
                }
            }
            ProEntitlement.Free, ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_snippet")
        }
    }
}
```

- [ ] **Step 4: Wire PlayerScreen to consume the navigation event + add a Snip button.**

Read `PlayerScreen.kt` and `PlayerTopBar.kt`, then:

- Add `onSnipTapped: () -> Unit` and `onOpenSnippetEditor: (String) -> Unit` params on `PlayerScreen`.
- In `PlayerScreen`, collect `viewModel.snippetEditorRoute` via `LaunchedEffect` and call `onOpenSnippetEditor(it)` for each emission.
- In `PlayerTopBar.kt`, add a Snip icon button next to the existing Bookmark button. Glyph = `KPIconName.Scissors` (add the icon in `KPIcon.kt` if it doesn't exist — fall back to a unicode-ish path tracing scissors / a `✂` overlay if needed; the visual is replaceable in Slice 4 when Claude Design ships waveform tokens).
- Plumb `onSnipTapped = viewModel::onSnipTapped` from PlayerScreen into PlayerTopBar.

- [ ] **Step 5: Compile + run unit tests.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL + all existing tests still PASS.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/ \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPIcon.kt
git commit -m "slice3(snippets): Player Snip button + Pro-gate + DI wiring"
```

---

### Task 9: SnippetEditor screen + ViewModel + Route

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt`

- [ ] **Step 1: Add the route.**

In `Routes.kt`:

```kotlin
@Serializable data class SnippetEditor(val snippetId: String) : Route
```

- [ ] **Step 2: Implement `SnippetEditorViewModel.kt`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetRenderLauncher
import app.kofipod.snippets.SnippetRepository
import app.kofipod.snippets.SnippetWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SnippetEditorUiState(
    val loading: Boolean = true,
    val snippet: Snippet? = null,
    val title: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val episodeDurationMs: Long = 0L,
    val format: SnippetFormat = SnippetFormat.MP3,
    val rendering: Boolean = false,
)

class SnippetEditorViewModel(
    private val snippetId: String,
    private val snippets: SnippetRepository,
    private val launcher: SnippetRenderLauncher,
) : ViewModel() {

    private val _state = MutableStateFlow(SnippetEditorUiState())
    val state: StateFlow<SnippetEditorUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val s = snippets.selectById(snippetId) ?: return@launch
            _state.value = SnippetEditorUiState(
                loading = false,
                snippet = s,
                title = s.title.orEmpty(),
                startMs = s.startMs,
                endMs = s.endMs,
                // We don't have episode duration in the snippet row; the editor
                // permits trims that exceed the persisted endMs. The render
                // service will re-clamp against the actual decoded duration.
                episodeDurationMs = s.endMs.coerceAtLeast(s.startMs + 1_000L),
                format = s.lastExportFormat ?: SnippetFormat.MP3,
            )
        }
    }

    fun setTitle(value: String) { _state.value = _state.value.copy(title = value) }

    fun nudgeStart(deltaMs: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs + deltaMs, cur.endMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    fun nudgeEnd(deltaMs: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs, cur.endMs + deltaMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    fun saveAndRender(onLaunchRender: () -> Unit) {
        val cur = _state.value
        val s = cur.snippet ?: return
        viewModelScope.launch {
            snippets.updateTitle(s.id, cur.title.takeIf { it.isNotBlank() })
            snippets.updateTrim(s.id, cur.startMs, cur.endMs)
            _state.value = cur.copy(rendering = true)
            launcher.enqueue(s.id)
            onLaunchRender()
        }
    }
}
```

- [ ] **Step 3: Implement the screen.**

`SnippetEditorScreen.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kofipod.snippets.SnippetWindow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SnippetEditorScreen(
    snippetId: String,
    onBack: () -> Unit,
) {
    val viewModel: SnippetEditorViewModel = koinViewModel { parametersOf(snippetId) }
    val state by viewModel.state.collectAsState()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snippet") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            TrimRow(
                label = "Start",
                value = state.startMs,
                onMinus5 = { viewModel.nudgeStart(-5_000L) },
                onMinus1 = { viewModel.nudgeStart(-1_000L) },
                onPlus1 = { viewModel.nudgeStart(+1_000L) },
                onPlus5 = { viewModel.nudgeStart(+5_000L) },
            )

            TrimRow(
                label = "End",
                value = state.endMs,
                onMinus5 = { viewModel.nudgeEnd(-5_000L) },
                onMinus1 = { viewModel.nudgeEnd(-1_000L) },
                onPlus1 = { viewModel.nudgeEnd(+1_000L) },
                onPlus5 = { viewModel.nudgeEnd(+5_000L) },
            )

            Text(
                "Format: MP3  ·  MP4 coming soon",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveAndRender(onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.rendering,
            ) {
                Text(if (state.rendering) "Rendering…" else "Render & Share")
            }
        }
    }
}

@Composable
private fun TrimRow(
    label: String,
    value: Long,
    onMinus5: () -> Unit,
    onMinus1: () -> Unit,
    onPlus1: () -> Unit,
    onPlus5: () -> Unit,
) {
    Column {
        Text("$label: ${SnippetWindow.formatTimestampDeci(value)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus5) { Text("-5s") }
            OutlinedButton(onClick = onMinus1) { Text("-1s") }
            OutlinedButton(onClick = onPlus1) { Text("+1s") }
            OutlinedButton(onClick = onPlus5) { Text("+5s") }
        }
    }
}
```

> **Implementer note:** `koinViewModel { parametersOf(...) }` is the dependency injection pattern Slice 1's `BookmarkComposer` uses — confirm the import path matches by reading `BookmarkComposer.kt`. If the project uses a different Koin Compose helper, follow that.

- [ ] **Step 4: Wire the route in `KofipodNavHost.kt`.**

```kotlin
composable<Route.SnippetEditor> { backStackEntry ->
    val args = backStackEntry.toRoute<Route.SnippetEditor>()
    SnippetEditorScreen(
        snippetId = args.snippetId,
        onBack = { navController.popBackStack() },
    )
}
```

And update the PlayerScreen call site to pass `onOpenSnippetEditor = { id -> navController.navigate(Route.SnippetEditor(id)) }`.

- [ ] **Step 5: Compile + iOS check.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/ \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt
git commit -m "slice3(snippets): SnippetEditor screen + Route + nav wiring"
```

---

### Task 10: Episode Detail "Saved" section — render snippet rows alongside bookmarks

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` (or wherever the existing Slice 1 Saved section lives)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

- [ ] **Step 1: Read the existing Saved section.**

Run: `grep -rn "Saved\|bookmark\|Bookmark" composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/`

Identify the file that renders bookmark rows and the ViewModel field that publishes them.

- [ ] **Step 2: Extend `EpisodeDetailViewModel` to combine bookmarks + snippets.**

Add a sealed type:

```kotlin
sealed interface SavedItem {
    val createdAtMs: Long
    data class BookmarkItem(val bookmark: app.kofipod.bookmarks.Bookmark) : SavedItem {
        override val createdAtMs: Long get() = bookmark.createdAtMs
    }
    data class SnippetItem(val snippet: app.kofipod.snippets.Snippet) : SavedItem {
        override val createdAtMs: Long get() = snippet.createdAtMs
    }
}
```

In the existing init / state-flow construction, replace the bookmark-only Flow with a `combine` of bookmarks + snippets:

```kotlin
val saved: StateFlow<List<SavedItem>> = combine(
    bookmarkRepo.observeForEpisode(episodeId),
    snippetRepo.observeForEpisode(episodeId),
) { bms, sns ->
    (bms.map(SavedItem::BookmarkItem) + sns.map(SavedItem::SnippetItem))
        .sortedByDescending { it.createdAtMs }
}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

- [ ] **Step 3: Render snippet rows in the Saved section.**

In the screen file, switch on `SavedItem` and render each. Snippet row content: title, `mm:ss.0–mm:ss.0` window, and (if rendered) a small "MP3" chip; tap navigates to `Route.SnippetEditor(snippet.id)`. Bookmark row stays as today.

- [ ] **Step 4: Bump the `EpisodeDetailViewModel` factory in `CommonModule.kt`.**

Add `snippetRepo = get()` to the existing `viewModel { EpisodeDetailViewModel(...) }` block.

- [ ] **Step 5: Compile + tests.**

```
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL + all unit tests still PASS.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice3(snippets): per-episode Saved section renders snippets too"
```

---

### Task 11: Lint + final assemble + Paparazzi snapshot verify

**Files:** none changed (this task is verification only).

- [ ] **Step 1: Format + lint.**

```
./gradlew :composeApp:ktlintFormat :composeApp:detekt
```
Expected: BOTH pass. `detekt`'s `androidx.media3.*` ban applies to commonMain; all Media3 imports are in androidMain, so this is fine.

- [ ] **Step 2: Full Android assemble.**

```
./gradlew :composeApp:assembleDebug
```
Expected: BUILD SUCCESSFUL. APK at `composeApp/build/outputs/apk/foss/debug/composeApp-foss-debug.apk` (or whichever flavor first).

- [ ] **Step 3: iOS frameworks compile.**

```
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full unit-test sweep.**

```
./gradlew :composeApp:testDebugUnitTest
```
Expected: ALL pass. New tests added: 9 (window) + 10 (repo) + 5 (resolver) = 24. Existing tests should be untouched.

- [ ] **Step 5: Paparazzi.**

```
./gradlew :composeApp:verifyPaparazziDebug
```
Expected: Existing baselines (4) still pass. No new screen baselines required this slice — per CLAUDE.md, screen-level Paparazzi is intentionally deferred.

- [ ] **Step 6: Stage cleanly + final commit if anything changed.**

```bash
git status
# If clean, no commit needed.
```

---

## Self-review

After all tasks complete, run through:

1. **Spec coverage:** Each item in spec § F1 (lines 168–178) for the MP3 path is covered — Player Snip trigger ✓, snip-last-60s window ✓, editor with title + start/end + format toggle ✓, MP3 render via Media3 audio-only export ✓, persistence at editor entry ✓, foreground service with `mediaProcessing` FG type ✓, share sheet with episode-link caption ✓. MP4 path / waveform / caption pipeline / AirPod tap explicitly out of scope per slice plan line 378 — they're Slice 4.
2. **Migration safety:** v17 → v18 is additive (one new table, no triggers, no backfill). Existing rows untouched.
3. **iOS compile:** All `expect` declarations have iOS actuals (stubs). Detekt ban on `androidx.media3.*` covered (Transformer imports only in androidMain).
4. **DRY:** `SnippetWindow.clampWindow` is the single source of trim validation. Both `createDraftFromPlayer` and `updateTrim(...,durationMs)` go through it. Editor's `nudgeStart`/`nudgeEnd` go through it. The `Bookmark`/`Snippet` row patterns mirror each other — same id-gen approach, same FK cascade pattern, same with-context join shape.
5. **Pro gate:** `onSnipTapped()` follows the exact shape of `onBookmarkTapped()` (same when-branches, same paywall key naming).
6. **Test coverage:** Window math (9), repo CRUD + cascade (10), source resolution (5).

---

## Execution

Use **superpowers:subagent-driven-development**: dispatch a fresh implementer per task, then spec-compliance review, then code-quality review, before moving on. Final code-review pass after Task 11.
