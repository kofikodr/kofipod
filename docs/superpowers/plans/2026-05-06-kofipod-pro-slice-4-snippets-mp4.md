# Kofipod Pro Slice 4 — Snippets MP4 + waveform editor + caption pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the headline Snippets feature. Ship the MP4 render path through Media3 Transformer's `Composition` graph (cover bg + waveform card + caption overlay), redesign the editor around a draggable waveform with ▶ Preview / IN-OUT pill chips / multiline caption / MP4-MP3 segmented format chip with size estimates, fold publisher-transcript-then-Gemini-fallback into the caption pipeline, and replace the temporary Snip/Bookmark icons in `PlayerTopBar` with a Pro Actions chip row that wears PRO pill badges on Free and surfaces a dismissible NEW coachmark on first show.

**Architecture:** Extends the existing `app.kofipod.snippets` package without restructuring. Adds `SnippetCaptionRepository` (transcript-first → Gemini-via-`AudioUploadCoordinator` fallback → none, mirroring the path picker in `AiSummaryRepository`), a deterministic `WaveformGenerator` (pure Kotlin, seeded by snippet id — real audio-amplitude extraction is explicitly deferred), a Compose `SnippetWaveform` primitive with start/end drag handles + scrubber, a `SnippetFormatChip` segmented primitive with byte-size estimates, and `exportMp4(...)` on `SnippetExporter` (Android actual = Transformer `Composition` with `ImageOverlay`+`TextOverlay`+`OverlayEffect`). Player chrome gains a new `PlayerProActionsRow` composable and a `PlayerProTipBanner` (dismissible, persisted via `SettingsRepository`). Editor + render service route by `SnippetFormat`. No schema migration needed — `Snippet.lastExportFormat` is already wire-string and `SnippetFormat.MP4` is additive.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight 2.0.2, Koin, Media3 Transformer 1.5.1 (Android-only, video Composition graph: image-source MediaItem + OverlayEffect), kotlinx.coroutines, Ktor (transcript fetch — already wired), Gemini Files API (caption-fallback transcription — already wired through `AudioUploadCoordinator` + `GeminiClient`).

---

## Scope discipline

This slice is the design-fidelity slice for Snippets. **In scope:**

- MP4 render path (audio + cover image as video track + waveform card overlay + caption text overlay).
- Waveform editor primitive with start/end drag handles + ▶ Preview + IN/OUT pill chips.
- Multiline caption field (default = transcript-derived line nearest start; user can edit; persisted as `captionOverride`).
- MP4/MP3 segmented format chip with byte-size estimates (e.g. "MP4 · 3.4 MB", "MP3 · 0.6 MB").
- Caption pipeline: publisher transcript (via existing `HttpTranscriptFetcher` + `episode.transcriptUrl`) → Gemini-via-`AudioUploadCoordinator` fallback (when key present + audio downloaded) → none (still renders, just no overlay text).
- Player Pro Actions chip row (Snip + Bookmark, with PRO pill badges on Free, replacing the TopBar buttons).
- NEW dismissible tip-banner under the chip row on first show (persists dismissal in `SettingsRepository`).
- Episode Detail Saved section: snippet rows show format + size badges (e.g. `0:42 · From make to bazel · MP4 · 3.4 MB`).
- End-to-end emulator pipeline smoke test: tap Snip → Editor opens with waveform → trim → Render & Share → MP4 file appears in `cacheDir/snippets/` → system share sheet fires.

**Out of scope** (and explicitly deferred):

- **Real audio-amplitude extraction** for the waveform → **Slice 4.5 / post-MVP**. Slice 4 ships a deterministic placeholder waveform seeded by `snippet.id` so the widget is fully wired and visually correct, but bars do not reflect the actual audio. The MP4 render bakes the same placeholder into the cover-card overlay. Documented as a known fidelity gap; `WaveformGenerator` is the seam where real extraction will plug in.
- **Karaoke-timed caption animation** (per-frame text reveal synchronized with audio). MP4 burns in a single static caption blob spanning the whole clip; transcript-line slicing for live highlighting is deferred to a later polish slice.
- **AirPod / wired-headset double-tap mapped through `MediaSession` custom command** → still deferred to a later slice; spec line 411 leaves the binding open. Player Snip chip remains the only trigger.
- **Long-press on the playback timeline as a Snip trigger** → deferred (same reason).
- **FTS5 indexing of snippet titles + caption overrides** → deferred to a Slice 4 follow-up. Slice 2 already handles transcript / summary / bookmark-note rows; adding snippet rows requires an `INSERT INTO LibrarySearchIndex` trigger on `Snippet` which is its own focused change.
- **Library "Snippets" entry-point row** (analogous to Bookmarks) → not in this slice. Saved section in Episode Detail is the only aggregation surface for snippets in v1.0.
- **SAF / "save MP4 to gallery" flow** → not in scope. Share sheet is the only egress surface.

**No schema migration.** `Snippet.sq` already accepts arbitrary wire strings in `lastExportFormat`. `SnippetFormat.MP4` is additive.

Anything not on these lists is **in** scope.

---

## Visual design reference

The bundled `docs/kofipod-pro-ui-design.html` is the source of truth for every UI in this slice. Per the spec mandate at `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` § "How to consult this reference (mandatory before implementing any Pro UI)", the design tiles MUST be rendered and screenshotted before implementation. **Task 1 below is that capture step**, and every subsequent UI task references the captured paths.

The pre-captured screenshots (already on disk from the planning step) are:

| Tile | Path |
|---|---|
| Snippet editor — idle (populated form) | `/tmp/kofipod-design-slice4-snippet-editor-idle.png` |
| Snippet editor — rendering (62% progress) | `/tmp/kofipod-design-slice4-snippet-editor-rendering.png` |
| Snippet editor — complete (Ready · Share) | `/tmp/kofipod-design-slice4-snippet-editor-complete.png` |
| Snippet editor — error (treat as: re-tap Render) | `/tmp/kofipod-design-slice4-snippet-editor-error.png` |
| Snippet editor — rendering, dark mode | `/tmp/kofipod-design-slice4-snippet-editor-rendering-dark.png` |
| Player — Pro Actions, Pro-gated (PRO badges + NEW coachmark) | `/tmp/kofipod-design-slice4-player-pro-actions-pro-gated.png` |
| Player — Pro Actions, unlocked (active Snip) | `/tmp/kofipod-design-slice4-player-pro-actions-unlocked.png` |
| Player — Pro Actions, dark mode | `/tmp/kofipod-design-slice4-player-pro-actions.png` |
| Player — Now Playing baseline (pre-Pro, for diff) | `/tmp/kofipod-design-slice4-player-now-playing-baseline.png` |
| Episode Detail — Saved section with snippet row | `/tmp/kofipod-design-slice4-episode-detail-saved.png` |
| Episode Detail — Saved, dark mode | `/tmp/kofipod-design-slice4-episode-detail-saved-dark.png` |
| Episode Detail — baseline (pre-Pro, for diff) | `/tmp/kofipod-design-slice4-episode-detail-baseline.png` |
| Bookmarks list — loaded (visual primitives) | `/tmp/kofipod-design-slice4-bookmarks-loaded.png` |
| Paywall — idle (for the "Snip & share clips · MP4 or MP3" line item) | `/tmp/kofipod-design-slice4-paywall-idle.png` |

**Design oddities locked in by this plan:**

- The `snippet-editor-error` tile shows no inline error UI — this slice surfaces render failure via a Compose Snackbar at the bottom of `SnippetEditorScreen`, not an inline banner. The CTA reverts to "Render & Share" so the user can retry. (Recorded discrepancy: design omits visual error treatment.)
- The `snippet-editor-complete` and `snippet-editor-error` tiles render the bottom CTA as a panel below the phone bezel. **We do not reproduce the literal bezel break** — buttons stay inside the phone area like the `idle` and `rendering` tiles. (Recorded discrepancy: cosmetic-only design liberty.)
- The IDLE tile shows a populated form with caret in the title field. We treat IDLE as "form ready, user can tap Render & Share" (same CTA as design); a freshly-opened editor with default `createDraftFromPlayer` data IS the IDLE state.
- The MP4 helper copy "MP4 includes a generated waveform card with the show art." appears under format chips on rendering/complete/error variants. We render it as a 12sp `c.textMute` line below the chip row, on all states.

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformSamples.kt` | Plain `data class WaveformSamples(val bars: FloatArray)`. 64 bars, each `0.0–1.0`. Used by both the editor primitive and the MP4 render overlay so they always show the same waveform for a given snippet. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformGenerator.kt` | Pure Kotlin. `fun generate(seed: String, barCount: Int = 64): WaveformSamples`. Deterministic — `kotlin.random.Random(seed.hashCode())` then a smoothing pass so the bars look like a real envelope, not pure noise. Real audio-amplitude extraction explicitly deferred; this is the seam where it'll plug in. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSizeEstimator.kt` | Pure Kotlin. `fun estimateBytes(format: SnippetFormat, durationMs: Long): Long`. MP3 = 128 kbps × duration; MP4 = ~1.5 Mbps × duration (audio + 720p cover-card video at low motion). `fun formatBytes(bytes: Long): String` returns "0.6 MB" / "3.4 MB". |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaption.kt` | Sealed `CaptionResolution`: `FromTranscript(text: String)`, `FromGemini(text: String)`, `None(reason: NoneReason)`. `enum class NoneReason { NoTranscript, NoAudioDownloaded, NoGeminiKey, GeminiFailed }` for diagnostics-only logging (no UI surface in v1.0). |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPicker.kt` | Pure-Kotlin path picker. Given `(episode, isAudioDownloaded, hasGeminiKey)` returns the path the repo should take: `Path.Transcript` / `Path.Gemini` / `Path.None`. Mirrors the picker in `AiSummaryRepository.runResume(...)`. Unit tested. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionRepository.kt` | Owner of the caption pipeline. `suspend fun resolveFor(snippet: Snippet, episode: Episode): CaptionResolution`. Dispatches via `SnippetCaptionPicker`, then either fetches transcript via `HttpTranscriptFetcher` + slices to `[startMs, endMs]`, OR calls `AudioUploadCoordinator.acquire(...)` then `GeminiClient.generateFromAudio(...)` with a transcription prompt scoped to the snippet window. Returns the produced text — caller decides whether to overlay it. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPrompts.kt` | Holds the Gemini prompt copy for the caption-fallback path. Single function `transcriptionPrompt(startMs: Long, endMs: Long): String`. Centralised so a future change to the prompt is one-liner. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/TranscriptSlicer.kt` | Pure Kotlin. `fun sliceForWindow(transcript: String, startMs: Long, endMs: Long): String?`. Greedy line-slicer: if transcript looks like WebVTT/SRT (has `00:01:23.456` cues), pick the cue nearest `startMs`. Otherwise return the first 200 chars (fallback for plain-text transcripts). Returns null on empty input. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetWaveform.kt` | Compose primitive. Canvas-rendered waveform bars; pink fill inside the `[startMs, endMs]` window, neutral fill outside. Two `Modifier.draggable` handles (Start, End) that clamp to `[0, durationMs]` and never cross. Scrubber line at current playhead position. Exposes `onStartChanged(Long)` / `onEndChanged(Long)` callbacks. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetTrimChips.kt` | The IN/OUT pill chips below the waveform showing `IN 18:42` / `OUT 19:24` / `0:42 selected` (the right-side selection-duration pill). Pure presentational; takes `startMs`/`endMs` and renders. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetFormatChip.kt` | Segmented pill chip. Two segments: MP4 / MP3. Each segment shows label + size estimate (computed via `SnippetSizeEstimator`). Selected segment carries pink pill background. Helper line below: "MP4 includes a generated waveform card with the show art." |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetPreviewControl.kt` | The ▶ Preview button next to the waveform. When tapped, plays the source audio clipped to `[startMs, endMs]` via the existing `KofipodPlayer`. Uses `Modifier.pointerInput` only for click; playback is handled by the VM. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProActionsRow.kt` | The Pro Actions chip row that lives below `PlayerTransport`, beside `SpeedPanel` / `SleepPanel` / queue counter. Contains the Snip + Bookmark icons. On Free, each icon wears a small pink "PRO" pill badge anchored top-right. On Pro, plain icon. Tapping invokes the existing `onSnipTapped` / `onBookmarkTapped` callbacks (no behaviour change — only the surface moves). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProTipBanner.kt` | The dismissible NEW coachmark below the chip row: pink `+` avatar + "NEW · Tap Snip to clip this moment, Bookmark to save it." Visible only when `SettingsRepository.proTipDismissedAt` is null. Dismiss button writes the timestamp. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/RenderProgress.kt` | Sealed `RenderProgress`: `Idle`, `InFlight(snippetId, fraction)`, `Complete(snippetId, path, format)`, `Failed(snippetId, message)`. Replaces the current "fire-and-forget" launcher → service handoff with an observable `StateFlow<RenderProgress>` exposed by `SnippetRenderLauncher` so the editor can render-stay-onscreen and show progress instead of returning to the Player immediately. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/WaveformGeneratorTest.kt` | Unit tests: deterministic for same seed; bars in `[0,1]`; bar count matches param; smoothing produces no two adjacent identical values for a non-degenerate seed. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSizeEstimatorTest.kt` | Unit tests for byte math and the human-readable formatter (handles `< 1 MB`, `>= 1 MB`, `>= 100 MB`). |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionPickerTest.kt` | Unit tests covering: transcript-only / audio-only / both / neither / Gemini-key-missing combinations. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/TranscriptSlicerTest.kt` | Unit tests: WebVTT cue selection, SRT cue selection, plain-text fallback, empty-input null. |
| `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionRepositoryTest.kt` | Unit tests with fakes for `HttpTranscriptFetcher` + `AudioUploadCoordinator` + `GeminiClient` covering all three resolution paths. |

### Modified files

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt` | Add `MP4(wire = "mp4", mimeType = "video/mp4", fileExtension = "mp4")` to `SnippetFormat` enum. Order: `MP4` first, `MP3` second (the design treats MP4 as the headline). |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt` | Add `suspend fun exportMp4(snippet: Snippet, sourceUriOrPath: String, outputPath: String, coverArtUriOrPath: String, captionText: String?, waveformSamples: WaveformSamples, onProgress: (Float) -> Unit = {}): Result<String>` to the `expect class`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt` | Implement `exportMp4` via Media3 Transformer `Composition`: image-source `MediaItem` for cover (clipped to clip duration), audio source `MediaItem` (clipped to `[startMs, endMs]`), `OverlayEffect` containing a `BitmapOverlay` (waveform card pre-rendered to a Bitmap from `WaveformSamples`) and a `TextOverlay` (caption). Use the same `Transformer.Listener` + `CompletableDeferred` bridge as `exportMp3`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/WaveformBitmapRenderer.kt` (NEW under androidMain) | `fun renderWaveformCard(samples: WaveformSamples, coverPath: String?, widthPx: Int, heightPx: Int): Bitmap`. Used by `exportMp4` to bake the waveform overlay onto the cover before passing it to Transformer. Android-only because `Bitmap` is Android-only. |
| `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt` | Add `exportMp4` actual stub: `throw NotImplementedError("Snippets not yet supported on iOS")`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt` | Route by `snippet.lastExportFormat` (or the format the editor saved). When MP4: collect cover-art path from podcast artwork (via `Episode` / `Podcast`), generate waveform via `WaveformGenerator`, resolve caption via `SnippetCaptionRepository`, then call `exportMp4(...)`. When MP3: existing `exportMp3` path unchanged. Update notification copy: "Rendering MP4 snippet…" / "Rendering MP3 snippet…". |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt` | Promote from fire-and-forget to observable: keep `enqueue(snippetId)` but add `val progress: StateFlow<RenderProgress>` updated by the service via `SnippetRenderProgressBus` (singleton in commonMain backed by a `MutableStateFlow`). |
| `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt` | Wire `progress` to the bus. Service publishes `InFlight` on start, `Complete`/`Failed` on `Transformer.Listener` callbacks. |
| `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderProgressBus.kt` (NEW under commonMain — listed here under "Modified" because it's coupled to the launcher contract change) | Singleton `object` exposing a `MutableStateFlow<RenderProgress>` and `internal fun publish(...)`. Service writes; launcher reads. iOS-safe (pure Kotlin). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorScreen.kt` | Full redesign per `/tmp/kofipod-design-slice4-snippet-editor-idle.png`. Drop title TextField (title is now a smaller field above the waveform), drop ±1s/±5s buttons, drop "MP3 · MP4 coming soon" hint. Add: top bar with show-meta header (artwork thumb + title + show name + duration pill from current position), `SnippetWaveform`, `SnippetTrimChips`, `SnippetPreviewControl` row, multiline caption `OutlinedTextField`, `SnippetFormatChip`, helper line, primary CTA (Cancel + Render & Share). When `RenderProgress.InFlight`: replace bottom CTA with progress bar + Rendering… + Cancel. When `Complete`: replace with "Ready · opening share sheet" + Cancel + Share. When `Failed`: snackbar + revert CTA to Render & Share. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorViewModel.kt` | Extend state: `caption: String`, `format: SnippetFormat = MP4`, `waveform: WaveformSamples`, `progress: RenderProgress = Idle`. Methods: `setCaption(value)`, `setFormat(value)`, `setStart(ms)` / `setEnd(ms)` (replace nudge methods — drag handles emit absolute positions), `previewToggle()` (uses `KofipodPlayer.play(...)` clipped to window — preview re-uses the main player; tapping ▶ pauses the original episode and plays the snippet preview, tapping again returns to the episode). Subscribe to `SnippetRenderLauncher.progress` filtered by `snippetId` and update state. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt` | After `PlayerTransport` and before `PlayerBottomBar`, add `PlayerProActionsRow` and `PlayerProTipBanner`. Pass `entitlement` (collected from `ProEntitlementRepository.state`), `onSnipTapped`, `onBookmarkTapped`, plus the dismiss state + dismiss callback for the banner. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt` | **Remove** Snip + Bookmark icon buttons. Top bar reverts to `[Back] · NOW PLAYING title block · [More menu]` (the speed-cycle + sleep-timer + queue chips are unchanged because they live in `PlayerBottomBar`, not `PlayerTopBar`). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt` | Expose `entitlement: StateFlow<ProEntitlement>` (delegate to `pro.state`) so the chip row can render PRO badges. Add `isProTipDismissed: StateFlow<Boolean>` from `SettingsRepository.proTipDismissedAt`. Add `dismissProTip()`. The existing `onSnipTapped` / `onBookmarkTapped` are unchanged. |
| `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt` | Add `fun proTipDismissedAt(): Flow<Long?>` and `suspend fun setProTipDismissedAt(epochMs: Long)`. Backed by `androidx.datastore` if that's already in use, else SharedPreferences via the same pattern as existing `skipForwardSeconds`/`skipBackSeconds`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` | In the existing snippet row of `SavedSection`, add format + size badges to the row text. Format reads `MP4` or `MP3` (from `snippet.lastExportFormat`); size formatted via `SnippetSizeEstimator.formatBytes(File(snippet.lastExportPath).length())`. Drafts (un-rendered) show no badge. |
| `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` | Register `single { WaveformGenerator() }`, `single { SnippetCaptionPicker() }`, `single { SnippetCaptionRepository(get(), get(), get(), get(), get()) }` (deps: `EpisodeSource`, `DownloadRepository`, `HttpTranscriptFetcher`, `AudioUploadCoordinator`, `GeminiClient` + `AiConfigRepository`). Bump `SnippetEditorViewModel` factory: it now needs `KofipodPlayer` (for ▶ Preview) and `WaveformGenerator`. Bump `PlayerViewModel` factory: it now needs `SettingsRepository` already (yes — verify) but no NEW deps; only the exposed flows change. Wire `SnippetRenderProgressBus` as `single { SnippetRenderProgressBus }` (object reference). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPIcon.kt` | Add `KPIconName.Play` if not present (verify; the player already has it). Add `KPIconName.PauseSmall` for ▶ Preview toggle if needed. Add `KPIconName.X` (tip-banner dismiss) — verify whether `Close` already exists. |
| `composeApp/src/commonMain/kotlin/app/kofipod/share/Sharer.kt` | (No change.) `shareFile(title, path, mimeType, captionText?)` already supports any MIME; the service passes `video/mp4` for MP4 snippets. |
| `composeApp/src/androidMain/AndroidManifest.xml` | (No change.) Foreground service entry already declares `mediaProcessing\|dataSync` and the `FOREGROUND_SERVICE_MEDIA_PROCESSING` permission is already granted. |
| `composeApp/src/androidMain/res/xml/file_paths.xml` | (No change.) `<cache-path name="snippets" path="snippets/"/>` already covers `.mp4`. |

---

## Tasks

Each task ends with a `git commit -m`. Don't squash. The pre-commit hook runs `ktlintFormat` + `detekt` on staged Kotlin — let it run; if it fails, fix and create a NEW commit. Confirm `git config --get core.hooksPath` returns `scripts/git-hooks` before the first commit (per memory `feedback_kofipod_worktree_hooks.md`).

---

### Task 1: Verify design tiles are captured (mandatory per spec)

**Files:** none (verification step).

The spec at `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` § "How to consult this reference" mandates that any plan derived from it captures the relevant design tiles before implementation. The capture has already been performed; this task confirms the tiles exist on disk and are referenced by subsequent tasks.

- [ ] **Step 1: Verify all 14 expected screenshots exist.**

Run:
```bash
for f in \
  /tmp/kofipod-design-slice4-snippet-editor-idle.png \
  /tmp/kofipod-design-slice4-snippet-editor-rendering.png \
  /tmp/kofipod-design-slice4-snippet-editor-complete.png \
  /tmp/kofipod-design-slice4-snippet-editor-error.png \
  /tmp/kofipod-design-slice4-snippet-editor-rendering-dark.png \
  /tmp/kofipod-design-slice4-player-pro-actions-pro-gated.png \
  /tmp/kofipod-design-slice4-player-pro-actions-unlocked.png \
  /tmp/kofipod-design-slice4-player-pro-actions.png \
  /tmp/kofipod-design-slice4-player-now-playing-baseline.png \
  /tmp/kofipod-design-slice4-episode-detail-saved.png \
  /tmp/kofipod-design-slice4-episode-detail-saved-dark.png \
  /tmp/kofipod-design-slice4-episode-detail-baseline.png \
  /tmp/kofipod-design-slice4-bookmarks-loaded.png \
  /tmp/kofipod-design-slice4-paywall-idle.png \
; do
  test -s "$f" || { echo "MISSING: $f"; exit 1; }
done
echo "All 14 design tiles present."
```

Expected output: `All 14 design tiles present.`

- [ ] **Step 2: Re-capture if missing.**

If any tile is missing, re-run the capture by dispatching the `seo-visual` agent with the prompt template stored at the head of this slice's planning notes (see "Visual design reference" above). The capture script at `/tmp/kofipod_capture_isolated.py` is the known-working pipeline (launches a fresh Chromium per tile, scrolls into centre view to defeat IntersectionObserver lazy-render).

- [ ] **Step 3: No commit for this task.** It's a verification gate, not a code change.

---

### Task 2: Add `SnippetFormat.MP4` + size estimator

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSizeEstimator.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSizeEstimatorTest.kt`

- [ ] **Step 1: Write the failing tests for size estimator.**

```kotlin
// composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSizeEstimatorTest.kt
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetSizeEstimatorTest {
    @Test
    fun mp3_estimate_uses_128_kbps() {
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP3, 60_000L)
        // 128 kbps = 16_000 B/s → 60s = 960_000 B (within ±10% for codec overhead)
        assertTrue(bytes in 900_000..1_050_000, "got $bytes")
    }

    @Test
    fun mp4_estimate_uses_about_1_5_mbps() {
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP4, 60_000L)
        // 1.5 Mbps ≈ 187_500 B/s → 60s ≈ 11.25 MB; allow ±15%
        assertTrue(bytes in 9_500_000..13_000_000, "got $bytes")
    }

    @Test
    fun mp4_42s_clip_matches_design_label_3_4_MB_to_4_MB_range() {
        // design copy says "MP4 · 3.4 MB" for a 0:42 clip — tolerate 2.5–5 MB
        val bytes = SnippetSizeEstimator.estimateBytes(SnippetFormat.MP4, 42_000L)
        val mb = bytes.toDouble() / 1_000_000.0
        assertTrue(mb in 2.5..5.0, "got $mb MB")
    }

    @Test
    fun formatBytes_under_1_MB_shows_KB() {
        assertEquals("640 KB", SnippetSizeEstimator.formatBytes(640_000L))
    }

    @Test
    fun formatBytes_megabyte_range_shows_one_decimal() {
        assertEquals("3.4 MB", SnippetSizeEstimator.formatBytes(3_400_000L))
    }

    @Test
    fun formatBytes_over_100_MB_drops_decimal() {
        assertEquals("123 MB", SnippetSizeEstimator.formatBytes(123_000_000L))
    }
}
```

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetSizeEstimatorTest"`
Expected: FAIL — `SnippetSizeEstimator` not defined.

- [ ] **Step 2: Add `MP4` to `SnippetFormat`.**

Open `composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt` and replace the `SnippetFormat` enum body (currently has only MP3) with:

```kotlin
enum class SnippetFormat(val wire: String, val mimeType: String, val fileExtension: String) {
    /**
     * Video export. Cover-art bg + generated waveform card overlay + caption
     * text overlay. Composition graph lives in [SnippetExporter.exportMp4]
     * (Android = Media3 Transformer). Default for new snippets in the editor —
     * the design positions MP4 as the headline format.
     */
    MP4(wire = "mp4", mimeType = "video/mp4", fileExtension = "mp4"),

    /**
     * Audio-only export. Despite the enum name `MP3` (chosen for user-facing
     * familiarity and forward compatibility with a future libmp3lame muxer),
     * the actual container is M4A (AAC-in-MP4) — that's what Media3
     * Transformer's bundled muxer produces reliably. The MIME `audio/mp4`
     * matches the bytes; share targets handle it correctly.
     */
    MP3(wire = "mp3", mimeType = "audio/mp4", fileExtension = "m4a"),
    ;

    companion object {
        fun fromWire(value: String?): SnippetFormat? = entries.firstOrNull { it.wire == value }
    }
}
```

- [ ] **Step 3: Implement `SnippetSizeEstimator`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSizeEstimator.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Pure-Kotlin byte-size estimator for rendered snippets. Used by the editor's
 * format chip to label each segment with an estimated size (e.g. "MP4 · 3.4 MB")
 * before the user commits to render. Estimates are intentionally rough — the
 * actual MP4 size depends on cover-art compressibility and the bundled muxer's
 * choices — but they're stable enough that a user can compare formats.
 */
object SnippetSizeEstimator {
    /** Bytes per millisecond, indexed by format. MP3 ≈ 128 kbps; MP4 ≈ 1.5 Mbps (audio + low-motion 720p video). */
    private const val MP3_BYTES_PER_MS: Double = 16.0 // 128_000 bps / 8 / 1_000
    private const val MP4_BYTES_PER_MS: Double = 187.5 // 1_500_000 bps / 8 / 1_000

    fun estimateBytes(format: SnippetFormat, durationMs: Long): Long {
        val perMs = when (format) {
            SnippetFormat.MP3 -> MP3_BYTES_PER_MS
            SnippetFormat.MP4 -> MP4_BYTES_PER_MS
        }
        return (durationMs.coerceAtLeast(0L) * perMs).toLong()
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / 1_000_000.0
        return when {
            mb >= 100.0 -> "${mb.toInt()} MB"
            mb >= 1.0 -> "%.1f MB".format(mb)
            else -> "${(bytes / 1_000).toInt()} KB"
        }
    }
}
```

- [ ] **Step 4: Run tests.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetSizeEstimatorTest"`
Expected: PASS — 6 tests pass.

- [ ] **Step 5: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/Snippet.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetSizeEstimator.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetSizeEstimatorTest.kt
git commit -m "slice4(snippets): SnippetFormat.MP4 + size estimator"
```

---

### Task 3: Waveform generator (deterministic placeholder)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformSamples.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformGenerator.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/WaveformGeneratorTest.kt`

- [ ] **Step 1: Write the failing tests.**

```kotlin
// composeApp/src/test/kotlin/app/kofipod/snippets/WaveformGeneratorTest.kt
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformGeneratorTest {
    private val gen = WaveformGenerator()

    @Test
    fun deterministic_for_same_seed() {
        val a = gen.generate("snip-abc123", barCount = 64)
        val b = gen.generate("snip-abc123", barCount = 64)
        assertTrue(a.bars.contentEquals(b.bars))
    }

    @Test
    fun different_seeds_produce_different_output() {
        val a = gen.generate("snip-abc123")
        val b = gen.generate("snip-def456")
        assertTrue(!a.bars.contentEquals(b.bars))
    }

    @Test
    fun bars_are_in_unit_range() {
        val w = gen.generate("snip-test", barCount = 64)
        for (v in w.bars) assertTrue(v in 0.0f..1.0f, "bar $v out of [0,1]")
    }

    @Test
    fun bar_count_matches_param() {
        val w = gen.generate("snip-x", barCount = 32)
        assertEquals(32, w.bars.size)
    }

    @Test
    fun smoothing_avoids_constant_runs() {
        // After smoothing, no run of 4+ adjacent identical values for a real
        // (non-degenerate) seed — proves the smoother isn't producing flat
        // sections that would render as visual gaps.
        val w = gen.generate("snip-real", barCount = 64)
        var run = 1
        var maxRun = 1
        for (i in 1 until w.bars.size) {
            if (w.bars[i] == w.bars[i - 1]) run++ else run = 1
            if (run > maxRun) maxRun = run
        }
        assertTrue(maxRun < 4, "constant run of $maxRun bars")
    }
}
```

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.WaveformGeneratorTest"`
Expected: FAIL — `WaveformGenerator` not defined.

- [ ] **Step 2: Implement `WaveformSamples`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformSamples.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * 64-bar amplitude envelope used by the editor waveform widget and the MP4
 * render's waveform-card overlay. Each bar is in `[0,1]`. Same seed always
 * produces the same samples so the editor and the rendered MP4 show the
 * identical visual.
 *
 * Slice 4 ships these as a deterministic placeholder seeded by `snippet.id`.
 * Real audio-amplitude extraction is the seam at [WaveformGenerator] — when
 * it lands in a later slice, the editor and the renderer change shape on the
 * same frame because both already consume `WaveformSamples`.
 */
data class WaveformSamples(val bars: FloatArray) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is WaveformSamples && bars.contentEquals(other.bars))

    override fun hashCode(): Int = bars.contentHashCode()
}
```

- [ ] **Step 3: Implement `WaveformGenerator`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformGenerator.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.abs
import kotlin.random.Random

/**
 * Produces a deterministic [WaveformSamples] envelope from a seed string.
 * Slice 4 uses this for both the editor preview (Compose Canvas) and the MP4
 * render overlay — the two surfaces always show the same waveform for a given
 * snippet because they both call this with `snippet.id`.
 *
 * The output is intentionally NOT a real audio amplitude extraction: that's
 * the deferred Slice 4.5 work. The visuals look like a plausible podcast
 * envelope (varying bars with smoothed transitions) without requiring a
 * MediaCodec decode step.
 */
class WaveformGenerator {
    fun generate(seed: String, barCount: Int = DEFAULT_BAR_COUNT): WaveformSamples {
        require(barCount > 0) { "barCount must be positive" }
        val rand = Random(seed.hashCode())
        // Step 1: raw uniform in [0.15, 1.0] — bias away from zero so no bar
        // disappears entirely.
        val raw = FloatArray(barCount) { 0.15f + rand.nextFloat() * 0.85f }
        // Step 2: 3-tap smoothing pass — a real envelope has correlated
        // neighbours, so a 3-tap moving average kills the white-noise look.
        val smoothed = FloatArray(barCount)
        for (i in 0 until barCount) {
            val l = if (i == 0) raw[i] else raw[i - 1]
            val r = if (i == barCount - 1) raw[i] else raw[i + 1]
            smoothed[i] = (l + raw[i] + r) / 3f
        }
        // Step 3: nudge any accidentally-equal adjacent values apart by 1%
        // so the smoothing-avoids-constant-runs invariant holds for any seed.
        for (i in 1 until barCount) {
            if (abs(smoothed[i] - smoothed[i - 1]) < EPS) {
                smoothed[i] = (smoothed[i] + NUDGE).coerceAtMost(1f)
            }
        }
        return WaveformSamples(smoothed)
    }

    private companion object {
        const val DEFAULT_BAR_COUNT = 64
        const val EPS = 0.001f
        const val NUDGE = 0.02f
    }
}
```

- [ ] **Step 4: Run tests.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.WaveformGeneratorTest"`
Expected: PASS — 5 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformSamples.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/WaveformGenerator.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/WaveformGeneratorTest.kt
git commit -m "slice4(snippets): deterministic waveform placeholder generator"
```

---

### Task 4: Caption picker (path-selection logic)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaption.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPicker.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionPickerTest.kt`

- [ ] **Step 1: Write the failing tests.**

```kotlin
// composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionPickerTest.kt
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetCaptionPickerTest {
    private val picker = SnippetCaptionPicker()

    @Test
    fun transcript_url_present_picks_transcript_path() {
        val p = picker.pick(
            transcriptUrl = "https://x.com/transcript.vtt",
            isAudioDownloaded = true,
            hasGeminiKey = true,
        )
        assertEquals(SnippetCaptionPicker.Path.Transcript, p)
    }

    @Test
    fun no_transcript_but_audio_downloaded_and_key_picks_gemini() {
        val p = picker.pick(
            transcriptUrl = null,
            isAudioDownloaded = true,
            hasGeminiKey = true,
        )
        assertEquals(SnippetCaptionPicker.Path.Gemini, p)
    }

    @Test
    fun no_transcript_no_audio_picks_none() {
        val p = picker.pick(
            transcriptUrl = null,
            isAudioDownloaded = false,
            hasGeminiKey = true,
        )
        assertEquals(SnippetCaptionPicker.Path.None, p)
    }

    @Test
    fun no_transcript_audio_downloaded_but_no_key_picks_none() {
        val p = picker.pick(
            transcriptUrl = null,
            isAudioDownloaded = true,
            hasGeminiKey = false,
        )
        assertEquals(SnippetCaptionPicker.Path.None, p)
    }

    @Test
    fun blank_transcript_url_treated_as_missing() {
        val p = picker.pick(
            transcriptUrl = "   ",
            isAudioDownloaded = true,
            hasGeminiKey = true,
        )
        assertEquals(SnippetCaptionPicker.Path.Gemini, p)
    }
}
```

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetCaptionPickerTest"`
Expected: FAIL.

- [ ] **Step 2: Implement `SnippetCaption.kt`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaption.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Output of [SnippetCaptionRepository.resolveFor]. The render service
 * consumes this and only burns text into the MP4 when [FromTranscript] /
 * [FromGemini] is returned. [None] is informational; rendering proceeds
 * without a caption overlay.
 */
sealed interface CaptionResolution {
    data class FromTranscript(val text: String) : CaptionResolution
    data class FromGemini(val text: String) : CaptionResolution
    data class None(val reason: NoneReason) : CaptionResolution
}

enum class NoneReason {
    NoTranscript,
    NoAudioDownloaded,
    NoGeminiKey,
    GeminiFailed,
}
```

- [ ] **Step 3: Implement `SnippetCaptionPicker.kt`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPicker.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Pure-Kotlin path picker mirroring [app.kofipod.ai.AiSummaryRepository]'s
 * transcript-vs-audio decision. Isolated for unit testability — production
 * is one [pick] call inside [SnippetCaptionRepository.resolveFor].
 */
class SnippetCaptionPicker {
    enum class Path { Transcript, Gemini, None }

    fun pick(
        transcriptUrl: String?,
        isAudioDownloaded: Boolean,
        hasGeminiKey: Boolean,
    ): Path {
        if (!transcriptUrl.isNullOrBlank()) return Path.Transcript
        if (isAudioDownloaded && hasGeminiKey) return Path.Gemini
        return Path.None
    }
}
```

- [ ] **Step 4: Run tests.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetCaptionPickerTest"`
Expected: PASS — 5 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaption.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPicker.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionPickerTest.kt
git commit -m "slice4(snippets): caption resolution sealed type + path picker"
```

---

### Task 5: Transcript slicer

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/TranscriptSlicer.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/TranscriptSlicerTest.kt`

- [ ] **Step 1: Write the failing tests.**

```kotlin
// composeApp/src/test/kotlin/app/kofipod/snippets/TranscriptSlicerTest.kt
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptSlicerTest {
    @Test
    fun webvtt_picks_cue_nearest_start() {
        val vtt = """
            WEBVTT

            00:00:10.000 --> 00:00:15.000
            First line spoken.

            00:01:30.000 --> 00:01:35.000
            The bazel adoption inflection point.

            00:03:00.000 --> 00:03:05.000
            Closing thoughts.
        """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 90_000L, endMs = 95_000L)
        assertEquals("The bazel adoption inflection point.", sliced)
    }

    @Test
    fun srt_picks_cue_nearest_start() {
        val srt = """
            1
            00:00:10,000 --> 00:00:15,000
            First line spoken.

            2
            00:01:30,000 --> 00:01:35,000
            The bazel adoption inflection point.
        """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(srt, startMs = 90_000L, endMs = 95_000L)
        assertEquals("The bazel adoption inflection point.", sliced)
    }

    @Test
    fun plain_text_returns_first_n_chars() {
        val plain = "This is a long monolithic transcript with no timing cues. ".repeat(10)
        val sliced = TranscriptSlicer.sliceForWindow(plain, startMs = 0L, endMs = 60_000L)
        assertEquals(true, (sliced?.length ?: 0) <= 200)
    }

    @Test
    fun empty_input_returns_null() {
        assertNull(TranscriptSlicer.sliceForWindow("", 0L, 1_000L))
        assertNull(TranscriptSlicer.sliceForWindow("   \n  ", 0L, 1_000L))
    }

    @Test
    fun no_cue_in_window_falls_back_to_nearest() {
        val vtt = """
            WEBVTT

            00:00:10.000 --> 00:00:15.000
            Only cue.
        """.trimIndent()
        val sliced = TranscriptSlicer.sliceForWindow(vtt, startMs = 60_000L, endMs = 70_000L)
        assertEquals("Only cue.", sliced)
    }
}
```

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.TranscriptSlicerTest"`
Expected: FAIL.

- [ ] **Step 2: Implement `TranscriptSlicer.kt`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/TranscriptSlicer.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.abs

/**
 * Picks a single ~one-line caption from a publisher transcript. Recognises
 * WebVTT (`HH:MM:SS.sss`) and SRT (`HH:MM:SS,sss`) cue formats; for plain
 * text, returns the first 200 chars as a coarse fallback. Returns null on
 * empty input — the caller renders without a caption overlay in that case.
 *
 * Slice 4 burns a single static caption into the MP4. Karaoke-timed reveal
 * is deferred — when it lands, this function will be replaced by a richer
 * cue-list slicer; the [String?] return shape stays.
 */
object TranscriptSlicer {
    private const val PLAIN_TEXT_LIMIT = 200

    fun sliceForWindow(transcript: String, startMs: Long, endMs: Long): String? {
        if (transcript.isBlank()) return null
        val cues = parseCues(transcript)
        if (cues.isEmpty()) {
            return transcript.trim().take(PLAIN_TEXT_LIMIT).ifBlank { null }
        }
        // Prefer a cue overlapping the window; else pick the cue with the
        // smallest distance from startMs.
        val overlapping = cues.firstOrNull { it.startMs in startMs..endMs || startMs in it.startMs..it.endMs }
        if (overlapping != null) return overlapping.text
        return cues.minByOrNull { abs(it.startMs - startMs) }?.text
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val CUE_LINE = Regex(
        """(\d{2}):(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[.,](\d{3})""",
    )

    private fun parseCues(transcript: String): List<Cue> {
        val lines = transcript.lines()
        val out = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = CUE_LINE.matchEntire(lines[i].trim())
            if (m != null) {
                val (h1, m1, s1, ms1, h2, m2, s2, ms2) = m.destructured
                val startMs = h1.toLong() * 3_600_000 + m1.toLong() * 60_000 + s1.toLong() * 1_000 + ms1.toLong()
                val endMs = h2.toLong() * 3_600_000 + m2.toLong() * 60_000 + s2.toLong() * 1_000 + ms2.toLong()
                val textLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank()) {
                    textLines.add(lines[j].trim())
                    j++
                }
                val text = textLines.joinToString(" ").trim()
                if (text.isNotEmpty()) out.add(Cue(startMs, endMs, text))
                i = j
            } else {
                i++
            }
        }
        return out
    }
}
```

- [ ] **Step 3: Run tests.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.TranscriptSlicerTest"`
Expected: PASS — 5 tests pass.

- [ ] **Step 4: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/TranscriptSlicer.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/TranscriptSlicerTest.kt
git commit -m "slice4(snippets): WebVTT/SRT-aware transcript slicer"
```

---

### Task 6: Caption repository (transcript fetch + Gemini fallback)

**Design adjustment from initial plan draft:** the production deps `DownloadRepository`, `AudioUploadCoordinator`, `GeminiClient`, `AiConfigRepository` are concrete *final* classes — they cannot be anonymously implemented in test-only fakes. To keep the repo unit-testable without hauling in MockK or the in-memory SQLite driver, this task introduces a small per-feature seam interface (`CaptionDeps`) that the repo depends on. Production wiring (in Task 16 DI) provides an adapter that delegates to the four final classes; tests fake `CaptionDeps` directly. `EpisodeSource` (interface) and `TranscriptFetcher` (`fun interface`) already exist in the codebase and are used as-is.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/CaptionDeps.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPrompts.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionRepository.kt`
- Create: `composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionRepositoryTest.kt`

- [ ] **Step 1: Define the small-interface seam `CaptionDeps`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/CaptionDeps.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Per-feature seam that [SnippetCaptionRepository] depends on. Production
 * wiring (CommonModule, Task 16 DI step) provides an adapter that delegates
 * to [app.kofipod.data.repo.DownloadRepository],
 * [app.kofipod.ai.AudioUploadCoordinator], [app.kofipod.ai.GeminiClient],
 * and [app.kofipod.ai.AiConfigRepository]. Tests fake [CaptionDeps] directly
 * — none of those four production classes are interfaces, so a small seam
 * is the only way to keep this repo unit-testable without MockK / DB driver.
 */
interface CaptionDeps {
    /** True iff the episode's audio is on local disk (i.e. fully downloaded). */
    suspend fun isAudioReadyFor(episodeId: String): Boolean

    /** The user's Gemini API key, or null when disconnected / not configured. */
    suspend fun currentGeminiKey(): String?

    /**
     * One-shot upload-then-transcribe. The implementation:
     *   1. resolves the API key (returns failure if missing),
     *   2. uses [app.kofipod.ai.AudioUploadCoordinator.acquire] to upload-or-cache
     *      the episode audio to Gemini Files API,
     *   3. calls [app.kofipod.ai.GeminiClient.generateFromAudio] with [prompt].
     *
     * Returns the transcribed text on success, a failure on any pipeline error.
     * The repository does not need to inspect *which* step failed —
     * `Result.failure` collapses all of them into [CaptionResolution.None]
     * with reason [CaptionResolution.NoneReason.GeminiFailed].
     */
    suspend fun transcribeForCaption(episodeId: String, prompt: String): Result<String>
}
```

- [ ] **Step 2: Implement the Gemini prompt holder.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPrompts.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Prompt copy for the caption-fallback path that hits Gemini when no
 * publisher transcript is available. Centralised so a future tuning pass
 * (or model upgrade) is a one-line change.
 */
object SnippetCaptionPrompts {
    fun transcriptionPrompt(startMs: Long, endMs: Long): String {
        val window = "${startMs / 1_000}s..${endMs / 1_000}s"
        return """
            Transcribe the audio between $window into one short caption (max 25 words).
            Return only the spoken words. No timestamps, no speaker labels, no quotation marks.
            If the segment is silent or unintelligible, return an empty string.
        """.trimIndent()
    }
}
```

- [ ] **Step 3: Implement `SnippetCaptionRepository`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionRepository.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.kofipod.ai.TranscriptFetcher
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.db.Episode
import kotlinx.coroutines.flow.firstOrNull

/**
 * Resolves a single caption string for a snippet, preferring publisher
 * transcript over Gemini transcription. Mirrors the path picker in
 * [app.kofipod.ai.AiSummaryRepository] but produces a single line, not a
 * structured Summary JSON.
 *
 * Caller (the render service) consumes [CaptionResolution] and either burns
 * the text into the MP4 overlay or proceeds without a caption.
 */
class SnippetCaptionRepository(
    private val episodes: EpisodeSource,
    private val transcripts: TranscriptFetcher,
    private val deps: CaptionDeps,
    private val picker: SnippetCaptionPicker = SnippetCaptionPicker(),
) {
    suspend fun resolveFor(snippet: Snippet): CaptionResolution {
        val episode = episodes.episodeFlow(snippet.episodeId).firstOrNull()
            ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        val isAudioReady = deps.isAudioReadyFor(snippet.episodeId)
        val key = deps.currentGeminiKey()

        val path = picker.pick(
            transcriptUrl = episode.transcriptUrl,
            isAudioDownloaded = isAudioReady,
            hasGeminiKey = !key.isNullOrBlank(),
        )

        return when (path) {
            SnippetCaptionPicker.Path.Transcript -> resolveFromTranscript(episode, snippet)
            SnippetCaptionPicker.Path.Gemini -> resolveFromGemini(snippet)
            SnippetCaptionPicker.Path.None -> {
                val reason = when {
                    !isAudioReady -> CaptionResolution.NoneReason.NoAudioDownloaded
                    key.isNullOrBlank() -> CaptionResolution.NoneReason.NoGeminiKey
                    else -> CaptionResolution.NoneReason.NoTranscript
                }
                CaptionResolution.None(reason)
            }
        }
    }

    private suspend fun resolveFromTranscript(
        episode: Episode,
        snippet: Snippet,
    ): CaptionResolution {
        val url = episode.transcriptUrl
            ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        val text = transcripts.fetch(url).getOrElse {
            return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        }
        val sliced = TranscriptSlicer.sliceForWindow(text, snippet.startMs, snippet.endMs)
            ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        return CaptionResolution.FromTranscript(sliced)
    }

    private suspend fun resolveFromGemini(snippet: Snippet): CaptionResolution {
        val prompt = SnippetCaptionPrompts.transcriptionPrompt(snippet.startMs, snippet.endMs)
        val text = deps.transcribeForCaption(snippet.episodeId, prompt).getOrElse {
            return CaptionResolution.None(CaptionResolution.NoneReason.GeminiFailed)
        }
        if (text.isBlank()) return CaptionResolution.None(CaptionResolution.NoneReason.GeminiFailed)
        return CaptionResolution.FromGemini(text.trim())
    }
}
```

> Implementer note on production wiring (Task 16 will also do this — recorded here so the contract is explicit when this commit lands): the production `CaptionDeps` binding lives in `CommonModule.kt` and is a single-method object that delegates to the four production classes:
>
> ```kotlin
> single<CaptionDeps> {
>     val episodes: EpisodeSource = get()
>     val downloads: DownloadRepository = get()
>     val coordinator: AudioUploadCoordinator = get()
>     val gemini: GeminiClient = get()
>     val config: AiConfigRepository = get()
>     object : CaptionDeps {
>         override suspend fun isAudioReadyFor(episodeId: String): Boolean =
>             !downloads.localPathFor(episodeId).isNullOrBlank()
>
>         override suspend fun currentGeminiKey(): String? = config.currentKey()
>
>         override suspend fun transcribeForCaption(
>             episodeId: String,
>             prompt: String,
>         ): Result<String> = runCatching {
>             val key = config.currentKey() ?: error("no Gemini key")
>             val episode = episodes.episodeNow(episodeId) ?: error("no episode")
>             val download = downloads.rowFor(episodeId) ?: error("no download row")
>             val acquired = coordinator.acquire(key, episode, download).getOrThrow()
>             gemini.generateFromAudio(key, acquired.fileUri, acquired.mimeType, prompt).getOrThrow()
>         }
>     }
> }
> single { SnippetCaptionRepository(get(), get(), get()) }
> ```
>
> `EpisodesRepository.episodeNow(episodeId): Episode?` already exists. `DownloadRepository.rowFor(episodeId): Download?` does NOT exist yet — the implementer of Task 6 must add it (one-line synchronous query into `db.downloadQueries`, mirroring `localPathFor`). The Task 6 commit therefore touches `DownloadRepository.kt` too. Add the new method via a SQLDelight named query in `Download.sq` if no `selectByEpisode` query exists, else reuse the existing one. **AiConfigRepository's API for reading the key is `suspend fun currentKey(): String?`, NOT `getKey()`** — the original plan draft said `getKey()` and that was wrong.

- [ ] **Step 4: Write fake-driven unit tests.**

```kotlin
// composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionRepositoryTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.kofipod.ai.TranscriptFetcher
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.RefreshResult
import app.kofipod.db.Episode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetCaptionRepositoryTest {
    @Test
    fun transcript_path_returns_FromTranscript() = runTest {
        val repo = makeRepoWith(
            transcriptUrl = "https://x.com/t.vtt",
            transcriptBody = """
                WEBVTT

                00:00:10.000 --> 00:00:15.000
                Bazel inflection.
            """.trimIndent(),
            audioDownloaded = false,
            geminiKey = null,
        )
        val r = repo.resolveFor(snippet(startMs = 10_000, endMs = 15_000))
        assertTrue(r is CaptionResolution.FromTranscript)
        assertEquals("Bazel inflection.", r.text)
    }

    @Test
    fun no_transcript_audio_and_key_present_uses_gemini() = runTest {
        val repo = makeRepoWith(
            transcriptUrl = null,
            transcriptBody = "",
            audioDownloaded = true,
            geminiKey = "k",
            geminiResponse = "Audio caption from Gemini.",
        )
        val r = repo.resolveFor(snippet())
        assertTrue(r is CaptionResolution.FromGemini)
        assertEquals("Audio caption from Gemini.", r.text)
    }

    @Test
    fun no_transcript_no_audio_returns_None_NoAudioDownloaded() = runTest {
        val repo = makeRepoWith(
            transcriptUrl = null, transcriptBody = "",
            audioDownloaded = false, geminiKey = "k",
        )
        val r = repo.resolveFor(snippet())
        assertTrue(r is CaptionResolution.None)
        assertEquals(CaptionResolution.NoneReason.NoAudioDownloaded, r.reason)
    }

    @Test
    fun no_transcript_audio_but_no_key_returns_None_NoGeminiKey() = runTest {
        val repo = makeRepoWith(
            transcriptUrl = null, transcriptBody = "",
            audioDownloaded = true, geminiKey = null,
        )
        val r = repo.resolveFor(snippet())
        assertTrue(r is CaptionResolution.None)
        assertEquals(CaptionResolution.NoneReason.NoGeminiKey, r.reason)
    }

    @Test
    fun gemini_failure_returns_None_GeminiFailed() = runTest {
        val repo = makeRepoWith(
            transcriptUrl = null, transcriptBody = "",
            audioDownloaded = true, geminiKey = "k",
            geminiResponse = null, // forces failure
        )
        val r = repo.resolveFor(snippet())
        assertTrue(r is CaptionResolution.None)
        assertEquals(CaptionResolution.NoneReason.GeminiFailed, r.reason)
    }

    @Test
    fun gemini_blank_response_treated_as_failure() = runTest {
        // Empty/blank Gemini output is not a useful caption — fall through to None.
        val repo = makeRepoWith(
            transcriptUrl = null, transcriptBody = "",
            audioDownloaded = true, geminiKey = "k",
            geminiResponse = "   ",
        )
        val r = repo.resolveFor(snippet())
        assertTrue(r is CaptionResolution.None)
        assertEquals(CaptionResolution.NoneReason.GeminiFailed, r.reason)
    }

    // Helpers -------------------------------------------------------------
    private fun snippet(startMs: Long = 0L, endMs: Long = 60_000L) = Snippet(
        id = "snip-test", episodeId = "ep-1", podcastId = "pc-1",
        startMs = startMs, endMs = endMs, title = null, captionOverride = null,
        createdAtMs = 1_000L, lastExportFormat = null, lastExportPath = null,
    )

    /**
     * Builds a `SnippetCaptionRepository` with three small fakes:
     * - A fake `EpisodeSource` that emits a single Episode with the given transcriptUrl.
     * - A fake `TranscriptFetcher` (`fun interface`) that returns `transcriptBody` if non-blank.
     * - A fake `CaptionDeps` parameterised by audioDownloaded / geminiKey / geminiResponse.
     *
     * The Episode constructor must match the SQLDelight-generated `Episode` row
     * shape EXACTLY — the implementer should peek at
     * `composeApp/build/generated/sqldelight/code/.../Episode.kt` (or run a
     * single test, see the compile error, and copy the constructor signature
     * from there) to get every field name right. The list below is what we
     * expect at the time of writing — adjust any field whose name has drifted.
     */
    private fun makeRepoWith(
        transcriptUrl: String?,
        transcriptBody: String,
        audioDownloaded: Boolean,
        geminiKey: String?,
        geminiResponse: String? = "ok",
    ): SnippetCaptionRepository {
        val episode = Episode(
            id = "ep-1",
            podcastId = "pc-1",
            guid = "g",
            title = "T",
            description = "",
            publishedAt = 0L,
            durationSec = 60L,
            enclosureUrl = "https://x/audio.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 1_000_000L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "", // NOT NULL with DEFAULT '' in Episode.sq
            chaptersUrl = null,
            transcriptUrl = transcriptUrl,
        )
        val episodes = object : EpisodeSource {
            override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(listOf(episode))
            override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(episode)
            override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())
            override suspend fun refresh(podcastId: String, feedId: Long, nowMillis: Long): RefreshResult =
                error("refresh() should not be called from SnippetCaptionRepository")
        }
        val transcripts = TranscriptFetcher { url ->
            if (transcriptBody.isNotBlank()) Result.success(transcriptBody)
            else Result.failure(IllegalStateException("empty transcript"))
        }
        val deps = object : CaptionDeps {
            override suspend fun isAudioReadyFor(episodeId: String): Boolean = audioDownloaded
            override suspend fun currentGeminiKey(): String? = geminiKey
            override suspend fun transcribeForCaption(
                episodeId: String,
                prompt: String,
            ): Result<String> =
                if (geminiResponse != null) Result.success(geminiResponse)
                else Result.failure(IllegalStateException("gemini failed"))
        }
        return SnippetCaptionRepository(episodes, transcripts, deps)
    }
}
```

> Implementer note: if the `Episode` constructor signature has drifted from the list above (SQLDelight regenerates from `Episode.sq`, so column adds/removes change the constructor), match whatever the current generated file requires — every field is non-default. If `EpisodeSource` has gained a new method since this plan was authored, override it with `error("unused")` rather than guessing semantics.

- [ ] **Step 5: Add `DownloadRepository.rowFor(episodeId): Download?` for the production wiring.**

`DownloadRepository.localPathFor(episodeId)` already exists. The Task 16 DI wiring needs the full `Download` row to pass to `AudioUploadCoordinator.acquire(...)`. Add a synchronous accessor (mirroring `localPathFor`):

```kotlin
// in composeApp/src/commonMain/kotlin/app/kofipod/data/repo/DownloadRepository.kt
fun rowFor(episodeId: String): Download? =
    db.downloadQueries.selectByEpisode(episodeId).executeAsOneOrNull()
```

Verify the `Download.sq` named query is `selectByEpisode` (or equivalent). If a query of that exact name doesn't exist, add one to `Download.sq`:

```sql
selectByEpisode:
SELECT * FROM Download WHERE episodeId = ?;
```

Pre-existing query names may differ — `selectById`, `selectAllForEpisode`, etc. Reuse rather than duplicate. The Task 6 commit therefore touches `DownloadRepository.kt` and possibly `Download.sq`.

- [ ] **Step 6: Run tests.**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.snippets.SnippetCaptionRepositoryTest"`
Expected: PASS — 6 tests pass (5 happy paths + the blank-response edge case).

- [ ] **Step 7: Compile-only green check (incl. iOS).**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/CaptionDeps.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionPrompts.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetCaptionRepository.kt \
        composeApp/src/test/kotlin/app/kofipod/snippets/SnippetCaptionRepositoryTest.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/data/repo/DownloadRepository.kt
# also stage Download.sq if you added a named query there
git commit -m "slice4(snippets): caption repo (transcript-first, Gemini fallback)"
```

---

### Task 7: Render-progress bus + observable launcher

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/RenderProgress.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderProgressBus.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.ios.kt`

- [ ] **Step 1: Define `RenderProgress`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/RenderProgress.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Observable render lifecycle. The editor subscribes via
 * [SnippetRenderLauncher.progress] and shows the design's rendering / complete
 * / error states inline instead of returning to Player on enqueue.
 */
sealed interface RenderProgress {
    data object Idle : RenderProgress
    data class InFlight(val snippetId: String, val fraction: Float) : RenderProgress
    data class Complete(val snippetId: String, val path: String, val format: SnippetFormat) : RenderProgress
    data class Failed(val snippetId: String, val message: String) : RenderProgress
}
```

- [ ] **Step 2: Define `SnippetRenderProgressBus`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderProgressBus.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton state bridge between the Android render service and the
 * common-code editor. Service writes via [publish]; the [SnippetRenderLauncher]
 * exposes the read side as [progress]. iOS code never publishes — Snippets
 * is Android-only — but the bus itself is pure Kotlin so iOS compile stays
 * green.
 */
object SnippetRenderProgressBus {
    private val _state = MutableStateFlow<RenderProgress>(RenderProgress.Idle)
    val state: StateFlow<RenderProgress> = _state.asStateFlow()

    fun publish(progress: RenderProgress) {
        _state.value = progress
    }
}
```

- [ ] **Step 3: Promote launcher to expose progress.**

Modify `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.StateFlow

/**
 * Schedules a render. [enqueue] is fire-and-forget on the launcher side; the
 * platform actual is responsible for making sure the render eventually
 * publishes to [SnippetRenderProgressBus]. [progress] is exposed here as a
 * convenience pass-through so the editor only depends on the launcher, not
 * the bus directly.
 */
expect class SnippetRenderLauncher {
    fun enqueue(snippetId: String)
    val progress: StateFlow<RenderProgress>
}
```

Modify the Android actual `SnippetRenderLauncher.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.StateFlow

actual class SnippetRenderLauncher(private val broadcaster: SnippetRenderBroadcaster) {
    actual fun enqueue(snippetId: String) {
        SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, fraction = 0f))
        broadcaster.start(snippetId)
    }

    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}
```

Modify the iOS actual `SnippetRenderLauncher.ios.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.StateFlow

actual class SnippetRenderLauncher {
    actual fun enqueue(snippetId: String) { /* iOS not yet supported */ }
    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}
```

- [ ] **Step 4: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/RenderProgress.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderProgressBus.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetRenderLauncher.ios.kt
git commit -m "slice4(snippets): observable RenderProgress bus on launcher"
```

---

### Task 8: `SnippetExporter.exportMp4` (Media3 Transformer composition graph)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/WaveformBitmapRenderer.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt`

- [ ] **Step 1: Add `exportMp4` to the `expect class`.**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

expect class SnippetExporter {
    suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>

    suspend fun exportMp4(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        coverArtUriOrPath: String?,
        captionText: String?,
        waveformSamples: WaveformSamples,
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
```

- [ ] **Step 2: Implement `WaveformBitmapRenderer` (Android only).**

```kotlin
// composeApp/src/androidMain/kotlin/app/kofipod/snippets/WaveformBitmapRenderer.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/**
 * Renders the cover-card frame used as the MP4's video track: cover art
 * (centred + cropped) with a waveform card overlaid in the lower third.
 * Pre-rendered to a Bitmap once per snippet then handed to Transformer as the
 * video source — Transformer then loops it across the clip duration.
 */
object WaveformBitmapRenderer {
    fun renderWaveformCard(
        samples: WaveformSamples,
        coverArtPath: String?,
        widthPx: Int = 1080,
        heightPx: Int = 1920,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F0F12")) // c.bg

        // 1. Cover art — centred crop into a square.
        val cover = coverArtPath?.let {
            runCatching { BitmapFactory.decodeFile(File(it).absolutePath) }.getOrNull()
        }
        if (cover != null) {
            val side = (widthPx * 0.75f).toInt()
            val left = (widthPx - side) / 2f
            val top = heightPx * 0.18f
            canvas.drawBitmap(
                cover,
                null,
                RectF(left, top, left + side, top + side),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }

        // 2. Waveform card — pink bars across the lower third.
        val barCount = samples.bars.size
        val cardTop = heightPx * 0.62f
        val cardBottom = heightPx * 0.78f
        val cardLeft = widthPx * 0.08f
        val cardRight = widthPx * 0.92f
        val barSpacing = (cardRight - cardLeft) / barCount
        val barWidth = barSpacing * 0.55f
        val cardHeight = cardBottom - cardTop
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F472B6") }
        for ((i, v) in samples.bars.withIndex()) {
            val h = cardHeight * v
            val x = cardLeft + i * barSpacing
            val y = cardTop + (cardHeight - h) / 2f
            canvas.drawRoundRect(x, y, x + barWidth, y + h, barWidth / 2f, barWidth / 2f, paint)
        }
        return bmp
    }
}
```

- [ ] **Step 3: Implement `exportMp4` in the Android actual.**

Open `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt` and add the new method body (keep the existing `exportMp3` unchanged):

```kotlin
@OptIn(DelicateCoroutinesApi::class)
actual suspend fun exportMp4(
    snippet: Snippet,
    sourceUriOrPath: String,
    outputPath: String,
    coverArtUriOrPath: String?,
    captionText: String?,
    waveformSamples: WaveformSamples,
    onProgress: (Float) -> Unit,
): Result<String> = withContext(Dispatchers.Main) {
    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()
    if (outputFile.exists()) outputFile.delete()

    // 1. Pre-render the cover-card bitmap to a temp PNG so Transformer can
    //    consume it as an image MediaItem (looped across the clip duration).
    val coverFrame = WaveformBitmapRenderer.renderWaveformCard(
        samples = waveformSamples,
        coverArtPath = if (coverArtUriOrPath?.startsWith("http") == true) null else coverArtUriOrPath,
    )
    val frameFile = File(outputFile.parentFile, "${snippet.id}-frame.png")
    frameFile.outputStream().use { coverFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }

    // 2. Audio MediaItem — clipped to [startMs, endMs], video removed.
    val audioItem = EditedMediaItem.Builder(
        MediaItem.Builder()
            .setUri(toUri(sourceUriOrPath))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(snippet.startMs)
                    .setEndPositionMs(snippet.endMs)
                    .build(),
            )
            .build(),
    ).setRemoveVideo(true).build()

    // 3. Image MediaItem — frame.png as the video track. Transformer loops the
    //    image to match the clip duration when both items are merged.
    val durationUs = (snippet.endMs - snippet.startMs) * 1_000L
    val imageItem = EditedMediaItem.Builder(
        MediaItem.fromUri(Uri.fromFile(frameFile)),
    )
        .setDurationUs(durationUs)
        .setFrameRate(VIDEO_FRAME_RATE)
        .setEffects(
            Effects(
                /* audioProcessors */ emptyList(),
                buildVideoEffects(captionText),
            ),
        )
        .build()

    val composition = Composition.Builder(
        EditedMediaItemSequence(imageItem),
        EditedMediaItemSequence(audioItem),
    ).build()

    val deferred = CompletableDeferred<Result<String>>()

    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .addListener(
            object : Transformer.Listener {
                override fun onCompleted(c: Composition, exportResult: ExportResult) {
                    deferred.complete(Result.success(outputFile.absolutePath))
                }
                override fun onError(c: Composition, exportResult: ExportResult, e: ExportException) {
                    deferred.complete(Result.failure(e))
                }
            },
        )
        .build()

    val pollerJob = GlobalScope.launch(Dispatchers.Main) {
        val holder = ProgressHolder()
        while (!deferred.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress((holder.progress / 100f).coerceIn(0f, 1f))
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    try {
        transformer.start(composition, outputPath)
        val result = deferred.await()
        pollerJob.cancel()
        // Tidy up the staged frame file regardless of outcome.
        frameFile.delete()
        result
    } catch (t: Throwable) {
        pollerJob.cancel()
        try { transformer.cancel() } catch (_: Throwable) { }
        frameFile.delete()
        Result.failure(t)
    }
}

private fun buildVideoEffects(captionText: String?): com.google.common.collect.ImmutableList<androidx.media3.common.Effect> {
    val builder = com.google.common.collect.ImmutableList.builder<androidx.media3.common.Effect>()
    if (!captionText.isNullOrBlank()) {
        // TextOverlay shipped with Transformer — caption burns into all frames.
        builder.add(
            androidx.media3.effect.OverlayEffect(
                com.google.common.collect.ImmutableList.of(
                    androidx.media3.effect.TextOverlay.createStaticTextOverlay(
                        android.text.SpannableString(captionText),
                    ),
                ),
            ),
        )
    }
    return builder.build()
}

private companion object {
    const val VIDEO_FRAME_RATE = 30
}
```

> Implementer note: Media3 Transformer's exact API for "loop an image across a clip duration" can shift between minor versions. The version in use is `androidx.media3:media3-transformer:1.5.1` per `gradle/libs.versions.toml`. If `EditedMediaItem.Builder.setDurationUs` / `setFrameRate` shape doesn't match in 1.5.1, consult the Media3 docs and adjust — the goal is `audio + looped image + caption overlay → MP4`. The shape above is the documented pattern in 1.5.x. **Do NOT** attempt to add a second sequence with another image; one `EditedMediaItemSequence(imageItem)` (video) + one `EditedMediaItemSequence(audioItem)` (audio) is the canonical Composition.

- [ ] **Step 4: Add the iOS stub.**

```kotlin
// composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt
actual suspend fun exportMp4(
    snippet: Snippet,
    sourceUriOrPath: String,
    outputPath: String,
    coverArtUriOrPath: String?,
    captionText: String?,
    waveformSamples: WaveformSamples,
    onProgress: (Float) -> Unit,
): Result<String> = Result.failure(NotImplementedError("Snippets MP4 not yet supported on iOS"))
```

- [ ] **Step 5: Compile both targets.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetExporter.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetExporter.android.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/WaveformBitmapRenderer.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/SnippetExporter.ios.kt
git commit -m "slice4(snippets): exportMp4 via Media3 Transformer composition"
```

---

### Task 9: Render service — route by format

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt`

The service currently always calls `exportMp3` and reports progress only via the notification. Slice 4 adds: format routing, cover-art / caption / waveform resolution, and `SnippetRenderProgressBus` publishing.

- [ ] **Step 1: Inject the new collaborators.**

In `SnippetRenderService.kt` add to the by-inject block:

```kotlin
private val captions: SnippetCaptionRepository by inject()
private val waveforms: WaveformGenerator by inject()
private val podcasts: PodcastRepository by inject()
```

> Implementer note: confirm `PodcastRepository` exposes `artworkUrlFor(podcastId): String?` (or equivalent — the cover URL is needed for the MP4 frame). If the existing repo only exposes a flow, add the synchronous accessor in this same task.

- [ ] **Step 2: Replace `renderOne` to route by format.**

```kotlin
private suspend fun renderOne(snippetId: String) {
    val snippet = repo.selectById(snippetId) ?: return
    val episode = episodes.episodeFlow(snippet.episodeId).firstOrNull() ?: return

    val localPath = downloads.localPathFor(snippet.episodeId)
    val source = resolver.resolve(localPath = localPath, enclosureUrl = episode.enclosureUrl)
    val sourceUriOrPath = when (source) {
        is SnippetSource.Local -> source.path
        is SnippetSource.Remote -> source.url
        SnippetSource.None -> {
            SnippetRenderProgressBus.publish(RenderProgress.Failed(snippetId, "Audio unavailable"))
            return
        }
    }

    val format = snippet.lastExportFormat ?: SnippetFormat.MP4 // editor default
    val outputDir = File(cacheDir, "snippets").apply { mkdirs() }
    val outputFile = File(outputDir, "${snippet.id}.${format.fileExtension}")

    SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, fraction = 0f))

    val result = when (format) {
        SnippetFormat.MP3 -> exporter.exportMp3(
            snippet = snippet,
            sourceUriOrPath = sourceUriOrPath,
            outputPath = outputFile.absolutePath,
            onProgress = { f ->
                SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, f))
                updateProgressNotification(f)
            },
        )
        SnippetFormat.MP4 -> {
            val caption = snippet.captionOverride
                ?: when (val r = captions.resolveFor(snippet)) {
                    is CaptionResolution.FromTranscript -> r.text
                    is CaptionResolution.FromGemini -> r.text
                    is CaptionResolution.None -> null
                }
            val coverArt = podcasts.artworkUrlFor(snippet.podcastId)
            val waveform = waveforms.generate(seed = snippet.id)
            exporter.exportMp4(
                snippet = snippet,
                sourceUriOrPath = sourceUriOrPath,
                outputPath = outputFile.absolutePath,
                coverArtUriOrPath = coverArt,
                captionText = caption,
                waveformSamples = waveform,
                onProgress = { f ->
                    SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, f))
                    updateProgressNotification(f)
                },
            )
        }
    }

    result.fold(
        onSuccess = { path ->
            repo.setRendered(snippet.id, format, path)
            SnippetRenderProgressBus.publish(RenderProgress.Complete(snippetId, path, format))
            triggerShare(snippet.copy(lastExportFormat = format, lastExportPath = path), path, format)
        },
        onFailure = { t ->
            SnippetRenderProgressBus.publish(RenderProgress.Failed(snippetId, t.message ?: "Render failed"))
        },
    )
}

private fun triggerShare(snippet: Snippet, path: String, format: SnippetFormat) {
    val episodeUrl = "https://podcastindex.org/podcast/${snippet.podcastId}?episode=${snippet.episodeId}"
    sharer.shareFile(
        title = snippet.title ?: "Snippet",
        path = path,
        mimeType = format.mimeType,
        captionText = "${snippet.title ?: "Snippet"}\n$episodeUrl",
    )
}
```

- [ ] **Step 3: Update notification copy.**

In `buildProgressNotification`:

```kotlin
.setContentTitle("Rendering snippet")
.setContentText(if (format == SnippetFormat.MP4) "MP4 · $pct%" else "MP3 · $pct%")
```

(Pass `format` into the helper and persist it on the service via a `@Volatile var currentFormat: SnippetFormat = SnippetFormat.MP4` field set inside `renderOne`.)

- [ ] **Step 4: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/androidMain/kotlin/app/kofipod/snippets/SnippetRenderService.kt
git commit -m "slice4(snippets): render service routes by format + publishes progress"
```

---

### Task 10: SettingsRepository — `proTipDismissedAt`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt`

- [ ] **Step 1: Add the read + write methods following existing patterns.**

```kotlin
fun proTipDismissedAt(): Flow<Long?> = /* Flow over the underlying preference store */

suspend fun setProTipDismissedAt(epochMs: Long) { /* write through */ }
```

> Implementer note: the existing repo has `skipForwardSeconds(): Flow<Int>` etc. Mirror that pattern exactly — DataStore Preferences if that's already the backing store, else SharedPreferences via the existing helper. Do not invent a new persistence layer.

- [ ] **Step 2: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt
git commit -m "slice4(player): proTipDismissedAt setting"
```

---

### Task 11: `SnippetWaveform` Compose primitive + drag handles

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetWaveform.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetTrimChips.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetPreviewControl.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetFormatChip.kt`

Reference the design tile at `/tmp/kofipod-design-slice4-snippet-editor-idle.png` for the visual treatment of all four primitives. Match: the bar count (≈64), the pink-fill-on-selection, the round IN/OUT pills with monospace timestamps, the segmented MP4/MP3 chip with size estimates, the round ▶ button.

- [ ] **Step 1: `SnippetWaveform.kt` — Canvas bars + draggable start/end handles.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.kofipod.snippets.WaveformSamples
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetWaveform(
    samples: WaveformSamples,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val barCount = samples.bars.size
    val widthPxState = remember { androidx.compose.runtime.mutableStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 4.dp)
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { /* per-handle hit-testing happens onDrag */ },
                    onDrag = { change, _ ->
                        val w = widthPxState.value
                        if (w <= 0f || durationMs <= 0L) return@detectDragGestures
                        val ratio = (change.position.x / w).coerceIn(0f, 1f)
                        val tappedMs = (ratio * durationMs).toLong()
                        // Snap to whichever handle is closer.
                        val distStart = kotlin.math.abs(tappedMs - startMs)
                        val distEnd = kotlin.math.abs(tappedMs - endMs)
                        if (distStart <= distEnd) {
                            onStartChanged(tappedMs.coerceAtMost(endMs - MIN_WINDOW_MS))
                        } else {
                            onEndChanged(tappedMs.coerceAtLeast(startMs + MIN_WINDOW_MS))
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            widthPxState.value = size.width
            drawBars(
                bars = samples.bars,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                playheadMs = playheadMs,
                pinkColor = c.pink,
                neutralColor = c.surface,
                playheadColor = c.text,
            )
        }
    }
}

private fun DrawScope.drawBars(
    bars: FloatArray,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long?,
    pinkColor: androidx.compose.ui.graphics.Color,
    neutralColor: androidx.compose.ui.graphics.Color,
    playheadColor: androidx.compose.ui.graphics.Color,
) {
    val w = size.width
    val h = size.height
    val barCount = bars.size
    val barSpacing = w / barCount
    val barWidth = barSpacing * 0.6f
    bars.forEachIndexed { i, v ->
        val barCenterMs = ((i + 0.5f) / barCount * durationMs).toLong()
        val inWindow = barCenterMs in startMs..endMs
        val barH = h * v
        val x = i * barSpacing + (barSpacing - barWidth) / 2f
        val y = (h - barH) / 2f
        drawRoundRect(
            color = if (inWindow) pinkColor else neutralColor,
            topLeft = Offset(x, y),
            size = Size(barWidth, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
    if (playheadMs != null && durationMs > 0L) {
        val phX = (playheadMs.toFloat() / durationMs * w).coerceIn(0f, w)
        drawRect(playheadColor, Offset(phX - 1f, 0f), Size(2f, h))
    }
}

private const val MIN_WINDOW_MS = 1_000L
```

- [ ] **Step 2: `SnippetTrimChips.kt` — IN / OUT pills + selection-duration pill.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.SnippetWindow
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetTrimChips(
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(label = "IN", value = SnippetWindow.formatTimestampDeci(startMs))
        Pill(label = "OUT", value = SnippetWindow.formatTimestampDeci(endMs))
        Spacer(Modifier.width(0.dp))
        Pill(label = "", value = SnippetWindow.formatTimestampDeci(endMs - startMs) + " selected", filled = true)
    }
}

@Composable
private fun Pill(label: String, value: String, filled: Boolean = false) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (filled) c.pink else c.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                color = if (filled) c.bg else c.textMute,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            value,
            color = if (filled) c.bg else c.text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
```

- [ ] **Step 3: `SnippetPreviewControl.kt` — round ▶/⏸ button.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetPreviewControl(
    isPlaying: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.pink)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(
            name = if (isPlaying) KPIconName.Pause else KPIconName.Play,
            color = c.bg,
            size = 22.dp,
        )
    }
}
```

> Implementer note: confirm `KPIconName.Play` and `KPIconName.Pause` already exist (the player uses both). If `Pause` is named differently (e.g. `PauseSmall`), use the existing name.

- [ ] **Step 4: `SnippetFormatChip.kt` — segmented MP4/MP3 chip with size estimates.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetSizeEstimator
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun SnippetFormatChip(
    selected: SnippetFormat,
    durationMs: Long,
    onSelect: (SnippetFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Column(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(4.dp),
        ) {
            for (format in SnippetFormat.entries) {
                val active = format == selected
                val size = SnippetSizeEstimator.formatBytes(SnippetSizeEstimator.estimateBytes(format, durationMs))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) c.pink else c.surface)
                        .clickable { onSelect(format) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        format.name,
                        color = if (active) c.bg else c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        " · $size",
                        color = if (active) c.bg else c.textMute,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "MP4 includes a generated waveform card with the show art.",
            color = c.textMute,
            fontSize = 12.sp,
        )
    }
}
```

- [ ] **Step 5: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetWaveform.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetTrimChips.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetPreviewControl.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetFormatChip.kt
git commit -m "slice4(snippets): waveform + trim chips + preview + format chip primitives"
```

---

### Task 12: Editor ViewModel rebuild (caption, format, progress, preview)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorViewModel.kt`

- [ ] **Step 1: Replace the existing VM with the design-aligned shape.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.snippets.RenderProgress
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import app.kofipod.snippets.SnippetRenderLauncher
import app.kofipod.snippets.SnippetRepository
import app.kofipod.snippets.SnippetWindow
import app.kofipod.snippets.WaveformGenerator
import app.kofipod.snippets.WaveformSamples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class SnippetEditorUiState(
    val loading: Boolean = true,
    val snippet: Snippet? = null,
    val title: String = "",
    val caption: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val episodeDurationMs: Long = 0L,
    val format: SnippetFormat = SnippetFormat.MP4,
    val waveform: WaveformSamples = WaveformSamples(FloatArray(0)),
    val previewing: Boolean = false,
    val previewPositionMs: Long? = null,
    val progress: RenderProgress = RenderProgress.Idle,
)

class SnippetEditorViewModel(
    private val snippetId: String,
    private val snippets: SnippetRepository,
    private val launcher: SnippetRenderLauncher,
    private val player: KofipodPlayer,
    private val waveformGen: WaveformGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(SnippetEditorUiState())
    val state: StateFlow<SnippetEditorUiState> = _state.asStateFlow()

    init {
        load()
        observeProgress()
    }

    private fun load() {
        viewModelScope.launch {
            val s = snippets.selectById(snippetId) ?: return@launch
            _state.value = SnippetEditorUiState(
                loading = false,
                snippet = s,
                title = s.title.orEmpty(),
                caption = s.captionOverride.orEmpty(),
                startMs = s.startMs,
                endMs = s.endMs,
                episodeDurationMs = s.endMs.coerceAtLeast(s.startMs + ONE_SECOND_MS),
                format = s.lastExportFormat ?: SnippetFormat.MP4,
                waveform = waveformGen.generate(seed = s.id),
            )
        }
    }

    private fun observeProgress() {
        viewModelScope.launch {
            launcher.progress
                .filter { it is RenderProgress.Idle ||
                    (it is RenderProgress.InFlight && it.snippetId == snippetId) ||
                    (it is RenderProgress.Complete && it.snippetId == snippetId) ||
                    (it is RenderProgress.Failed && it.snippetId == snippetId) }
                .collect { p -> _state.value = _state.value.copy(progress = p) }
        }
    }

    fun setTitle(value: String) { _state.value = _state.value.copy(title = value) }
    fun setCaption(value: String) { _state.value = _state.value.copy(caption = value) }
    fun setFormat(value: SnippetFormat) { _state.value = _state.value.copy(format = value) }
    fun setStart(ms: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(ms, cur.endMs, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }
    fun setEnd(ms: Long) {
        val cur = _state.value
        val w = SnippetWindow.clampWindow(cur.startMs, ms, cur.episodeDurationMs)
        _state.value = cur.copy(startMs = w.startMs, endMs = w.endMs)
    }

    /** Start preview playback from `startMs` via the main player. */
    fun previewToggle() {
        val cur = _state.value
        if (cur.previewing) {
            player.pause()
            _state.value = cur.copy(previewing = false)
            return
        }
        val s = cur.snippet ?: return
        viewModelScope.launch {
            // Reuse the main player; the caller's pause-on-exit is handled
            // by Player nav lifecycle. Preview clipping below is best-effort
            // (a real Media3 clipping config would require re-loading the
            // source), so we just seek to startMs and rely on the user to
            // tap again to stop.
            player.seekTo(cur.startMs)
            player.resume()
            _state.value = cur.copy(previewing = true)
        }
    }

    fun saveAndRender() {
        val cur = _state.value
        val s = cur.snippet ?: return
        viewModelScope.launch {
            snippets.updateTitle(s.id, cur.title.takeIf { it.isNotBlank() })
            snippets.updateCaptionOverride(s.id, cur.caption.takeIf { it.isNotBlank() })
            snippets.updateTrim(s.id, cur.startMs, cur.endMs)
            // Persist user-selected format on the row so the service knows
            // which exporter to call. The path column stays NULL until the
            // render completes — `setRendered` (called by the service on
            // success) overwrites both columns together.
            snippets.markFormatPending(s.id, cur.format)
            launcher.enqueue(s.id)
        }
    }

    fun cancelRender() {
        // Best-effort: the foreground service treats new enqueues as
        // overrides. Slice 4 has no separate "cancel" intent — flipping the
        // bus to Idle locally is the user-visible part.
        _state.value = _state.value.copy(progress = RenderProgress.Idle)
    }

    private companion object {
        private const val ONE_SECOND_MS = 1_000L
    }
}
```

> Implementer note: `SnippetRepository.setRendered` currently requires `format` + non-empty `path`. Add a new `fun markFormatPending(id: String, format: SnippetFormat)` method on the repo (and a corresponding `markFormatPending` named query in `Snippet.sq` that writes only the `lastExportFormat` column, leaving `lastExportPath` untouched). The editor calls `markFormatPending` before enqueuing render; the service overwrites both columns via the existing `setRendered` on completion. Do NOT loosen `setRendered` to accept blank path — its current contract ("rendered file exists at `path`") is what the Saved-section row relies on for the format/size badge.

- [ ] **Step 2: Compile-only green check.**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/SnippetRepository.kt \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/Snippet.sq
git commit -m "slice4(snippets): editor VM with caption/format/progress/preview"
```

---

### Task 13: Editor screen rebuild (waveform layout)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorScreen.kt`

Reference `/tmp/kofipod-design-slice4-snippet-editor-idle.png` and the rendering / complete tiles. Match: top bar with "Snippet" title; show-meta header (artwork thumb + episode title + show name + duration pill from current position); waveform; trim chips; preview-control + caption multiline; format chip; helper line; bottom CTA (Cancel + Render & Share / Rendering / Ready · Share).

- [ ] **Step 1: Replace the screen body.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.snippet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.snippets.RenderProgress
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SnippetEditorScreen(
    snippetId: String,
    onBack: () -> Unit,
    viewModel: SnippetEditorViewModel = koinViewModel(parameters = { parametersOf(snippetId) }),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.progress) {
        when (val p = state.progress) {
            is RenderProgress.Failed -> snackbarHost.showSnackbar("Render failed: ${p.message}")
            is RenderProgress.Complete -> { /* share sheet fires from service */ }
            else -> { /* nothing */ }
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = c.pink)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
        ) {
            SnippetEditorTopBar(onBack = onBack)

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                SnippetWaveform(
                    samples = state.waveform,
                    durationMs = state.episodeDurationMs,
                    startMs = state.startMs,
                    endMs = state.endMs,
                    playheadMs = state.previewPositionMs,
                    onStartChanged = viewModel::setStart,
                    onEndChanged = viewModel::setEnd,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SnippetTrimChips(startMs = state.startMs, endMs = state.endMs)
                    SnippetPreviewControl(isPlaying = state.previewing, onTap = viewModel::previewToggle)
                }

                OutlinedTextField(
                    value = state.caption,
                    onValueChange = viewModel::setCaption,
                    label = { Text("Caption") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    minLines = 2,
                    maxLines = 4,
                )

                SnippetFormatChip(
                    selected = state.format,
                    durationMs = (state.endMs - state.startMs),
                    onSelect = viewModel::setFormat,
                )
            }
        }

        // Bottom CTA strip — varies with progress.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bg).padding(20.dp)) {
            when (val p = state.progress) {
                is RenderProgress.InFlight -> RenderingStrip(
                    fraction = p.fraction,
                    onCancel = { viewModel.cancelRender(); onBack() },
                )
                is RenderProgress.Complete -> ReadyStrip(onShare = onBack)
                else -> IdleStrip(
                    onCancel = onBack,
                    onRenderAndShare = viewModel::saveAndRender,
                )
            }
        }

        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun IdleStrip(onCancel: () -> Unit, onRenderAndShare: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
        KPButton(label = "Render & Share", onClick = onRenderAndShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun RenderingStrip(fraction: Float, onCancel: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = c.pink)
        Text("Rendering ${(fraction * 100).toInt()}%", color = c.textMute, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onCancel, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Rendering…", onClick = { }, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun ReadyStrip(onShare: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ready · opening share sheet", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KPButton(label = "Cancel", onClick = onShare, style = KPButtonStyle.Outline, modifier = Modifier.weight(1f))
            KPButton(label = "Share", onClick = onShare, style = KPButtonStyle.PrimaryPink, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun SnippetEditorTopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text("Snippet", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
}
```

- [ ] **Step 2: Compile + assemble.**

Run: `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/snippet/SnippetEditorScreen.kt
git commit -m "slice4(snippets): editor screen rebuild (waveform + caption + format)"
```

---

### Task 14: Player Pro Actions chip row + NEW tip-banner

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProActionsRow.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProTipBanner.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt`

Reference `/tmp/kofipod-design-slice4-player-pro-actions-pro-gated.png` and `…unlocked.png`. Match: chip row position (between transport and existing speed/sleep chips), small pink "PRO" pill anchored top-right of each Pro icon when entitlement is `Free`/`Unknown`, NEW coachmark banner below the chip row with pink `+` avatar + "Tap Snip to clip this moment, Bookmark to save it." + dismiss `×`.

- [ ] **Step 1: Create `PlayerProActionsRow`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.pro.ProEntitlement
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun PlayerProActionsRow(
    entitlement: ProEntitlement,
    onSnipTapped: () -> Unit,
    onBookmarkTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProIconChip(icon = KPIconName.Scissors, label = "Snip", showProBadge = entitlement !is ProEntitlement.Pro, onClick = onSnipTapped)
        Spacer(Modifier.width(12.dp))
        ProIconChip(icon = KPIconName.Bookmark, label = "Bookmark", showProBadge = entitlement !is ProEntitlement.Pro, onClick = onBookmarkTapped)
    }
}

@Composable
private fun ProIconChip(
    icon: KPIconName,
    label: String,
    showProBadge: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(c.surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = icon, color = c.text, size = 22.dp)
        }
        if (showProBadge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.pink)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("PRO", color = c.bg, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
        }
    }
}
```

- [ ] **Step 2: Create `PlayerProTipBanner`.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
internal fun PlayerProTipBanner(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val c = LocalKofipodColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(c.pink),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = c.bg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NEW",
                color = c.pink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Tap Snip to clip this moment, Bookmark to save it.",
                color = c.text,
                fontSize = 12.sp,
            )
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Close, color = c.textMute, size = 16.dp)
        }
    }
}
```

> Implementer note: confirm `KPIconName.Close` exists. If not, add it as a thin "×" path; the existing scrubber/dismiss chrome must already use one.

- [ ] **Step 3: Strip Snip + Bookmark from `PlayerTopBar`.**

```kotlin
// (in PlayerTopBar.kt)
// remove: TopRoundButton(icon = KPIconName.Scissors, onClick = onSnip)
// remove: TopRoundButton(icon = KPIconName.Bookmark, onClick = onBookmark)
// remove: onSnip / onBookmark parameters from the @Composable signature
// keep:    Back · NOW PLAYING · More
```

- [ ] **Step 4: Wire `PlayerScreen` to insert the chip row + banner, and update VM exposure.**

Modifications to `PlayerViewModel.kt`:

```kotlin
val entitlement: StateFlow<ProEntitlement> = pro.state // already exists in pro repo

val isProTipDismissed: StateFlow<Boolean> =
    settings.proTipDismissedAt()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true) // default-dismissed until we know

fun dismissProTip() {
    viewModelScope.launch {
        settings.setProTipDismissedAt(Clock.System.now().toEpochMilliseconds())
    }
}
```

Modifications to `PlayerScreen.kt`: insert between `PlayerTransport` and `PlayerBottomBar`:

```kotlin
val ent by viewModel.entitlement.collectAsState()
val tipDismissed by viewModel.isProTipDismissed.collectAsState()

PlayerProActionsRow(
    entitlement = ent,
    onSnipTapped = viewModel::onSnipTapped,
    onBookmarkTapped = viewModel::onBookmarkTapped,
)
PlayerProTipBanner(
    visible = !tipDismissed,
    onDismiss = viewModel::dismissProTip,
)
```

Also remove `onSnip` / `onBookmark` from the `PlayerTopBar(...)` invocation in `PlayerScreen`.

- [ ] **Step 5: Compile + assemble.**

Run: `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProActionsRow.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerProTipBanner.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerTopBar.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt
git commit -m "slice4(player): Pro Actions chip row + NEW tip-banner"
```

---

### Task 15: Episode Detail Saved row — format + size badges

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt`

Reference `/tmp/kofipod-design-slice4-episode-detail-saved.png`. Snippet rows show: scissors icon, `0:42 · From make to bazel · MP4 · 3.4 MB`, with bookmark rows unchanged.

- [ ] **Step 1: Add the `FileSizer` expect/actual seam (commonMain consumes the size, Android computes it).**

```kotlin
// composeApp/src/commonMain/kotlin/app/kofipod/snippets/FileSizer.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/** Returns 0 when the path is unreadable / missing / on a stub platform. */
expect class FileSizer() {
    fun sizeOf(path: String): Long
}
```

```kotlin
// composeApp/src/androidMain/kotlin/app/kofipod/snippets/FileSizer.android.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import java.io.File

actual class FileSizer {
    actual fun sizeOf(path: String): Long =
        runCatching { File(path).length().coerceAtLeast(0L) }.getOrDefault(0L)
}
```

```kotlin
// composeApp/src/iosMain/kotlin/app/kofipod/snippets/FileSizer.ios.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

actual class FileSizer {
    actual fun sizeOf(path: String): Long = 0L
}
```

- [ ] **Step 2: Project the size into the Saved row's data class via `EpisodeDetailViewModel`.**

`SavedItem.SnippetItem` currently carries only the `Snippet`. Add a derived field:

```kotlin
// in SavedItem.kt
data class SnippetItem(val snippet: Snippet, val sizeBytes: Long) : SavedItem {
    override val createdAtMs: Long get() = snippet.createdAtMs
}
```

In `EpisodeDetailViewModel`, when mapping snippet rows to `SavedItem.SnippetItem`, compute `sizeBytes = snippet.lastExportPath?.let { fileSizer.sizeOf(it) } ?: 0L`. Inject `FileSizer` into the VM constructor and bump the Koin factory accordingly.

- [ ] **Step 3: Render the format + size badges in the Saved snippet row composable.**

The existing snippet-row composable inside `SavedSection` (in `EpisodeDetailScreen.kt`) takes the `SnippetItem` and renders duration + title. Add format/size badges only when the snippet is rendered (`item.sizeBytes > 0L && item.snippet.lastExportFormat != null`):

```kotlin
val format = item.snippet.lastExportFormat
val rendered = format != null && item.sizeBytes > 0L

if (rendered) {
    Spacer(Modifier.width(6.dp))
    Text("·", color = c.textMute)
    Spacer(Modifier.width(6.dp))
    Text(
        format!!.name,
        color = c.textMute,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.width(6.dp))
    Text(
        SnippetSizeEstimator.formatBytes(item.sizeBytes),
        color = c.textMute,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}
```

This keeps `EpisodeDetailScreen.kt` in commonMain with no `java.io.File` reference.

- [ ] **Step 4: Compile + assemble.**

Run: `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedItem.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/snippets/FileSizer.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/snippets/FileSizer.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/snippets/FileSizer.ios.kt
git commit -m "slice4(detail): snippet rows show format + size badges"
```

---

### Task 16: DI wiring + `ktlintFormat` + `detekt`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

- [ ] **Step 1: Register all new singletons + bumped factories.**

Add inside the existing `CommonModule.kt`:

```kotlin
single { WaveformGenerator() }
single { SnippetCaptionPicker() }
single { SnippetCaptionRepository(get(), get(), get(), get(), get(), get()) }
single { FileSizer() }

viewModel { (snippetId: String) ->
    SnippetEditorViewModel(
        snippetId = snippetId,
        snippets = get(),
        launcher = get(),
        player = get(),
        waveformGen = get(),
    )
}

// PlayerViewModel — already has 10 deps; only the public-flow surface
// changed in this slice, so the existing factory is unchanged. Confirm.
```

- [ ] **Step 2: Run lint format + detekt explicitly to catch anything before pre-commit.**

Run: `./gradlew :composeApp:ktlintFormat :composeApp:detekt`
Expected: BUILD SUCCESSFUL with zero violations.

- [ ] **Step 3: Run all unit tests.**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with all tests passing (existing 473 + ~30 new from Tasks 2/3/4/5/6).

- [ ] **Step 4: Compile both targets.**

Run: `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice4(di): wire caption repo + waveform gen + editor VM params"
```

---

### Task 17: Emulator end-to-end smoke test

**Files:** none (manual verification + commit a memory entry on success).

Per CLAUDE.md the expected workflow is: `assembleDebug → installDebug → adb shell uiautomator dump → pull → inspect bounds → adb shell input tap`. The Pixel_9a AVD must be running.

- [ ] **Step 1: Boot the emulator (if not running) and install.**

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a -no-snapshot -no-window > /tmp/emu.log 2>&1 &
~/Library/Android/sdk/platform-tools/adb wait-for-device
./gradlew :composeApp:installDebug
```

- [ ] **Step 2: Launch app + capture launch screenshot.**

```bash
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod/.MainActivity
sleep 3
~/Library/Android/sdk/platform-tools/adb shell screencap -p /sdcard/launch.png
~/Library/Android/sdk/platform-tools/adb pull /sdcard/launch.png /tmp/kofipod-slice4-launch.png
```

Expected: app launches without crash; logcat shows no `FATAL EXCEPTION`.

- [ ] **Step 3: Walk the snip-and-render flow.**

The exact taps depend on which podcast is in the test library. Subscribe via Search → tap an episode → press Play → wait 90s → return to Now Playing → tap Snip → editor opens with waveform + caption (transcript path if episode has one) → drag start handle → tap Render & Share → wait for system share sheet → cancel out → return to Player.

Capture screenshots at each major state and save under `/tmp/kofipod-slice4-{launch,player-actions,editor-idle,editor-rendering,share-sheet,saved-section}.png`. Use `uiautomator dump` to get real bounds before tapping.

- [ ] **Step 4: Validate the rendered file.**

```bash
~/Library/Android/sdk/platform-tools/adb shell run-as app.kofipod ls -la /data/user/0/app.kofipod/cache/snippets/
```

Expected: at least one `*.mp4` file present, size > 100 KB.

- [ ] **Step 5: No commit on this task.**

The memory update at slice close (Task 18) records the smoke-test result and screenshot paths. This task is a verification gate; passing means the slice is truly green.

---

### Task 18: Memory + push

**Files:**
- Modify: `/Users/ebernie/.claude/projects/-Users-ebernie-dev-podman/memory/project_kofipod.md`

- [ ] **Step 1: Append a Pro Slice 4 entry recording shipped scope, deferrals, smoke-test status, and commit range.**

Mention: every commit between the slice's first and last commit, the design fidelity gaps still open (real audio amplitude extraction, karaoke captions, AirPod tap-to-snip), the smoke-test paths under `/tmp/kofipod-slice4-*.png`, and the schema version (still 18 — no migration in this slice).

- [ ] **Step 2: Push to origin.**

Per the standing isolation rule (memory: `feedback_pro_isolation.md`), Pro slices stay on the worktree branch. Do not merge to master.

```bash
git push origin worktree-kofipodpro-pre0
```

- [ ] **Step 3: No commit needed beyond memory.** Memory writes are local-only and not part of the repo.

---

## Done conditions

A reviewer should be able to:

1. Open `/tmp/kofipod-design-slice4-snippet-editor-idle.png` next to the running editor and see the same five regions (title field, waveform, IN/OUT/duration pills + ▶, multiline caption, format chip + helper line).
2. Confirm `git log --oneline` shows ~16 slice4 commits between the plan commit and the memory commit.
3. Run `./gradlew :composeApp:testDebugUnitTest :composeApp:detekt :composeApp:compileKotlinIosSimulatorArm64` and see BUILD SUCCESSFUL with zero violations.
4. Find a `*.mp4` under `/data/user/0/app.kofipod/cache/snippets/` after walking the snip flow, with size in the SnippetSizeEstimator-predicted range.
5. Confirm `Snippet.lastExportFormat = "mp4"` for newly-rendered snippets and `Episode Detail` Saved section shows the `MP4 · 3.4 MB` badge on the row.
6. Confirm Player TopBar no longer carries Snip/Bookmark icons; the chip row + NEW banner are present below transport.
7. Confirm Free user tapping Snip or Bookmark from the chip row opens Paywall (not the editor).
