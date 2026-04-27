# Episode Detail Screen — Implementation Plan

Status: **Draft, awaiting confirmation on open questions.**
Date: 2026-04-27
Branch: `master` (per user direction; feature is small enough that slicing on master is fine).

## Why now

Today, tapping an episode row plays it instantly. There's no surface for description, chapters, transcript availability, share, mark-played, or delete-download — and no place for a future AI summary panel to live. The detail screen earns its keep on its own (it surfaces publisher data we already have but discard) and unblocks the AI feature without having to retrofit a per-episode UI later.

## Reference design

User-supplied mock (`/Users/ebernie/Downloads/episode-detail.png`). Pinned elements:

1. Top bar: back, share, overflow.
2. Podcast strip: small art tile · podcast name · `EP NNN` (right-aligned).
3. Category chip (pink caps) — **podcast-level** category, not episode-level.
4. Episode title (large bold).
5. Meta row: `APR 15 · 1h 04m · 58 MB · DOWNLOADED`. The `DOWNLOADED` token only appears when downloaded; the rest is always present.
6. Action row: primary `Play episode` pill + circular `mark played` (✓) + circular `delete download` (trash).
7. Description block (publisher show notes).
8. `CHAPTERS` section with row count, monospace timestamps, titles, optional subtitle separator (`·`).

## Where it plugs in

- **Existing entry points**: `PodcastDetailScreen` episode rows (`onClick = play(id)` today), `DownloadsScreen` rows. Both should change to navigate, not play.
- **New Route**: `Route.EpisodeDetail(val episodeId: String)` in `ui/nav/Routes.kt`.
- **NavHost entry**: `composable<Route.EpisodeDetail>` in `KofipodNavHost.kt`, with `onBack` + `onOpenPlayer`.
- **New ViewModel**: `EpisodeDetailViewModel` resolved via Koin parameterised factory, mirroring `PodcastDetailViewModel`.

## Data model gaps to fill

`Episode.sq` currently stores `description`, but drops these fields from `EpisodeFeed`:

| Field | Type | Source on `EpisodeFeed` | Use |
|---|---|---|---|
| `chaptersUrl` | `String?` | already there | Chapters fetch |
| `transcriptUrl` | `String?` | already there | Future AI fallback |
| `imageUrl` | `String` | `image` | Per-episode art (some feeds override podcast art per-ep) |

Migration is additive (nullable / NOT NULL DEFAULT '' as appropriate). Schema bump 8 → 9.

For chapter rows themselves, two options — see Open Question Q4.

## Open questions (need user sign-off before coding)

**Q1 — Tap behaviour change.** Right now tap-on-row = play immediately. Mock implies tap = open detail, play is explicit. Three options:

- **A:** Tap = open detail, no inline play on the row. Cleanest, but slowest path to "play this".
- **B:** Tap = open detail, *plus* keep the existing inline play icon on each row for fast-play. Mock-faithful for detail, preserves muscle memory.
- **C:** Tap title area = detail, tap small play icon = play. (This is what the existing row already does — `play` is wired to `onClick = { play(id) }` and there's already an `EpisodePlayButton`. Need to inspect to confirm.)

I'd default to **B** unless you say otherwise.

**Q2 — Category chip source.** Mock shows "TECHNOLOGY" — that's a podcast-level taxonomy from PodcastIndex (`PodcastFeed.categories`). Confirm the chip should reflect the **podcast's** primary category (not something synthesised per episode)? Default: yes.

**Q3 — `EP NNN` rendering.** `episodeNumber: Int?` is nullable on Episode. Drop the chip when null vs. show "EP —" vs. fall back to publish date alone? Default: drop chip when null.

**Q4 — Chapter persistence shape.** Two options:

- **4A:** No table. Cache chapters JSON in memory per-VM, refetch on each open. Simple, but jittery if offline.
- **4B:** New `EpisodeChapter.sq` table (`episodeId, startSec, title, imageUrl?, linkUrl?`) + a `ChaptersRepository` that fetches once on first open, persists, then serves from disk. Survives offline; one extra migration.

The 2.0 chapters spec is JSON with `startTime`, `title`, optional `img`, `url`, `endTime`. For the design mock we only render `startTime` + `title` (+ optional sub-title separator). I'd recommend **4B** for parity with how we treat episodes (DB-first), and because it keeps the episode-detail screen instant on subsequent opens.

**Q5 — Description HTML.** RSS descriptions are frequently HTML (`<p>`, `<a>`, `<br>`). The mock renders flat plain text. Three options:

- **5A:** Strip HTML tags, render as a single styled `Text`. Minimum-viable; loses links.
- **5B:** Strip HTML but linkify URLs + collapse whitespace.
- **5C:** Render a small subset of HTML (paragraphs + links) via Compose `AnnotatedString`.

I'd default to **5B** — clean, link-clickable, no third-party HTML renderer.

**Q6 — Chapters absent.** Most feeds don't ship chapters. Hide the section entirely vs. render an empty-state ("No chapters in this episode")? Default: hide.

**Q7 — "Mark played" semantics.** Set `PlaybackState.positionMs = durationSec * 1000`? Or add a `completed BOOLEAN` flag? Today, the row's "played" indicator (if any) is derived from PlaybackState — need to confirm. Default: set position to duration. (Lower-blast-radius, no schema change beyond what we're already doing.)

**Q8 — Where else does this screen open from?** Confirmed: episode rows on `PodcastDetailScreen`, and download rows on `DownloadsScreen`. Anything else (Continue listening cards, Search results)? Default: only those two for v1.

## Slices

Each slice is independently committable + emulator-verifiable.

### Slice 1 — Persist the missing episode fields (data only, no UI shift)

- Add `chaptersUrl`, `transcriptUrl`, `imageUrl` columns to `Episode.sq` (nullable / `NOT NULL DEFAULT ''`).
- New migration `9.sqm`. Bump CLAUDE.md schema line 8 → 9.
- Update `EpisodeFeed.toEpisode()` mapping (in `EpisodesRepository` / `PodcastDetailViewModel.persistRemoteEpisodes()`) to include the new fields.
- Update `Episode.kt` domain model.
- **Verify**: install on emulator, open any podcast detail, confirm rows render unchanged. Inspect SQLite for populated `chaptersUrl` on a feed that has them (e.g. *Podcasting 2.0*).

### Slice 2 — Episode detail screen scaffolding + nav

- New `Route.EpisodeDetail(episodeId)`.
- `EpisodeDetailViewModel(episodeId, episodes, podcasts, playback, downloads, sharer, player, appScope)`.
- `EpisodeDetailScreen` rendering: top bar, podcast strip, category chip, title, meta row, description, action row. **No chapters yet** — empty placeholder.
- `EpisodesRepository.observeById(id)`.
- Wire taps: `PodcastDetailScreen` row + `DownloadsScreen` row → navigate to `EpisodeDetail`. Per Q1 decision, may also keep an inline play icon on the row.
- `play()`, `markPlayed()`, `deleteDownload()`, `share()`, `download()` on the VM.
- **Verify**: install, tap an episode → screen opens, all data fields render, Play navigates to `Player`, Mark played updates the row indicator on back, Delete download disables when nothing to delete.

### Slice 3 — Chapters

- (If Q4 = 4B) Add `EpisodeChapter.sq`. Migration `10.sqm`. Schema 9 → 10.
- `ChaptersRepository`: fetch + parse Podcasting 2.0 JSON via Ktor, persist, expose `Flow<List<Chapter>>`.
- Reuse the shared `HttpClient` (no new client).
- Render chapters list in `EpisodeDetailScreen`. Tapping a chapter row when this episode is the active playback → seek; otherwise play this episode and seek.
- **Verify**: open an episode known to have chapters (any *Podcasting 2.0* episode by Adam Curry), confirm rows render. Open one without — section hides per Q6.

### Slice 4 — Polish + snapshots

- Paparazzi baseline for `EpisodeDetailScreen` (downloaded + not-downloaded, with chapters + without).
- Edge cases: very long titles, missing description, missing image, episode-art override vs. podcast art.
- ktlintFormat + detekt + verifyPaparazziDebug clean.
- Compose UI test (commonTest) for the action-row state machine.

## Out of scope (deferred)

- Transcript rendering — we'll persist `transcriptUrl` in Slice 1 but no UI yet. Owned by future AI work.
- AI summary panel — separate branch (`feat/ai-byok-gemini`), explicitly waits on this screen to land.
- Inline chapter art and chapter `url` link-out — render text-only in v1, revisit if user feedback asks for it.
- Episode notifications screen overflow / pin / hide-from-feed — outside the mock.

## Coordination with `feat/ai-byok-gemini`

- This work bumps schema to 9 (Slice 1) and possibly 10 (Slice 3, if Q4=4B).
- The AI branch's pending Slice 2 already targets `9.sqm` for `EpisodeAiSummary.sq`. Once this lands on master, the AI branch needs to be rebased and its migration retargeted to `10.sqm` or `11.sqm` depending on Q4.
- Action: after this feature merges, rebase `feat/ai-byok-gemini` on master and retarget the migration filename in one commit before resuming Slice 2.

## Slice 4 entry notes (post-compaction)

State captured here so a fresh session can resume Slice 4 without losing the in-flight context that doesn't appear in commit messages.

**Schema state on master**: bumped 8 → 10 (`9.sqm` adds `imageUrl/chaptersUrl/transcriptUrl` to Episode; `10.sqm` creates `EpisodeChapter`). `feat/ai-byok-gemini`'s pending Slice 2 migration must be retargeted from `9.sqm` to `11.sqm` once that branch is rebased.

**Slices 1–3 plus tests landed in `7992f12..b4d4e05`.** Code review (`feature-dev:code-reviewer`) and test audit (`test-quality-auditor`) ran post-Slice 3; review issues #1–#3 fixed in `10aec85`, audit recommendations covered in `b4d4e05`.

**Downloads tap-behaviour deviation.** Q8 originally said "both `PodcastDetailScreen` and `DownloadsScreen` rows tap → detail" in Slice 2. Slice 2 only changed `PodcastDetailScreen` because tap-to-play felt valuable in the Downloads context (curated content, fast-play UX). The user later asked for consistency, so `280efd2` retroactively changed Downloads too and deleted the now-orphaned `DownloadsViewModel.play()` along with its `KofipodPlayer`/`EpisodesRepository`/`PlaybackRepository` constructor params. Decision is settled — no need to revisit.

**Known-bad `cleanDescription` regex.** Code-review issue #4 (deferred — not fixed in `10aec85`). The current `<[^>]+>` tag-stripping regex in `EpisodeDetailScreen.kt` will eat literal `<` characters in description text (e.g. `"speed is <3x faster"` gets corrupted up to the next `>`). The in-file KDoc says "Slice 4 may upgrade this to a small AnnotatedString renderer that preserves links". This is the upgrade that fixes both the regex bug and the missing-link-rendering deferred from Q5. Recommend doing it as the first task in Slice 4 — it's the only outstanding *correctness* issue; everything else in Slice 4 is polish.

**Deferred from Slice 2 — category chip.** The pink "TECHNOLOGY" chip in the mock requires a `Podcast.primaryCategory` column (no current source for non-library podcasts). Trivial additive migration (`11.sqm` if done before AI rebase, otherwise `12.sqm`) + populate at `LibraryRepository.savePodcast` time. Open question for Slice 4 entry: land this now, or split into a separate slice? Defaulting to "land in Slice 4" is reasonable since it's a few lines.

**Slice 4 task checklist (suggested order):**
1. Replace `cleanDescription` with an `AnnotatedString` renderer that preserves paragraphs + linkifies URLs (fixes review issue #4 + Q5).
2. Add `Podcast.primaryCategory` column + render the category chip on the detail screen.
3. Edge cases: very long titles (>100 chars), missing description, missing podcast art (already handled by `KofipodArtwork` fallback — verify), per-episode art override vs. podcast art.
4. Paparazzi baseline for `EpisodeDetailScreen` — needs a `*Content` split that takes `EpisodeDetailUiState` as a parameter (matches existing screen-snapshot pattern). Two baselines (light/dark), three configurations (downloaded + chapters / not-downloaded + no-chapters / mid-download).
5. Compose UI test in `commonTest` for the action-row state machine (Play / Pause / Resume label transitions, Mark-played idempotence, Delete-vs-Download swap on download state change).
6. Final lint sweep: `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:testDebugUnitTest :composeApp:verifyPaparazziDebug :composeApp:compileKotlinIosSimulatorArm64`.
