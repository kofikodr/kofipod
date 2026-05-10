# Kofipod Pro — One-Time Unlock — Design Spec

**Date:** 2026-05-04
**Status:** Design locked, awaiting implementation plan
**Audience:** Claude Design (visual/UX) → implementation plan

## Goal & framing

Turn Kofipod into a **paid app without a subscription, without a server, without ads, and without altering the deal for current free users**. A one-time in-app purchase ("Kofipod Pro") unlocks a coherent set of local power-user features. Everything that works today stays free forever; Pro is purely additive.

Why this shape:

- **No backend.** The current Auto-Backup posture, BYOK Gemini posture, and zero-server-cost stance all stay intact. There is no sync, no hosted clip player, no hosted AI. Every Pro feature runs on-device.
- **One-time purchase, not subscription.** Subscriptions in this market produced documented backlash for Pocket Casts (lifetime members shown ads, Sept 2025) and pushed Podcast Addict to abandon its IAP for subs in Aug 2025 — leaving the "premium one-time-pay FOSS Android" slot empty. Kofipod fills it.
- **GPL-3.0 stays honest.** Anyone can build the Pro features from source. The Play Store binary with the unlock is what ~99% of users will pay for. No license-server check.
- **Cloud-tier compatibility.** v1 is local-only by intent, but the architecture is designed so a later optional Cloud subscription (sync, hosted snippet pages, hosted AI) can layer on without re-architecting Pro.

## Pricing & SKUs

Two non-consumable Google Play Billing v6+ products:

| SKU                    | Product ID               | Price (USD)    | Unlocks |
|------------------------|--------------------------|----------------|---|
| Kofipod Pro            | `kofipod_pro`            | **$12.99**     | All Pro features for the purchasing Google account |
| ~~Kofipod Pro Family~~ | ~~`kofipod_pro_family`~~ | ~~**$19.99**~~ | ~~All Pro features for the purchaser's Google Family group (up to 5 accounts)~~ |

**No subscription. No trial. No ads. No future "Pro 2.0 — buy again."** All v1.0 + v1.1 + future *Pro-tier* features ship as free updates to existing Pro buyers. (A potential later **Cloud** tier — sync, hosted clip pages, hosted AI — would be a separate subscription product, not a re-paywalling of Pro features.)

Restore Purchase happens automatically on app start and via a manual button in Settings. Entitlement is recovered through Play Billing on new devices, not through Auto Backup — this prevents device-clone bypass and removes the ambiguity of "is my purchase tied to my device or my account."

**Note: Family Plan dropped**

## Build flavors and distribution

The codebase is GPL-3.0-or-later. Anyone can build it from source. Rather than fight that with a license-server check (rejected — see decision log), Kofipod ships **two product flavors** in a single Gradle module. This is the AntennaPod / Bitwarden / Signal model and it works.

### Flavors

| Flavor | Audience | BillingClient dep | Pro features | Paywall sheet |
|---|---|---|---|---|
| `play` | Google Play Store buyers | yes | gated by real entitlement check | shown to Free users |
| `foss` | Source builds + F-Droid | **excluded** | unconditionally unlocked | never shown |

`build.gradle.kts`:

```kotlin
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
```

Source-set layout for `BillingClientPort`:

- `androidMain/.../BillingClientPort.kt` — `expect`-shaped interface only (no implementation).
- `playAndroid/.../BillingClientPort.kt` — real Google Play Billing v6+ implementation.
- `fossAndroid/.../BillingClientPort.kt` — stub returning `Pro(source = FossBuild)` unconditionally.

`ProEntitlementRepository` ends up with one production implementation that delegates to `BillingClientPort`; the FOSS port short-circuits the billing flow and the Paywall sheet is never reached because every paywalled action sees `Pro` already.

### Distribution channels

| Channel | Flavor | Status |
|---|---|---|
| Google Play Store | `play` | **primary**, the revenue product |
| F-Droid | `foss` | secondary, eligible only because the FOSS flavor excludes the proprietary Play Billing dep. Submission lands later, after Pro launch stabilises. |
| Source build | `foss` | supported and documented; standard `./gradlew :composeApp:assembleFossDebug` |
| **GitHub Releases APK** | — | **discontinued.** Pre-built APKs are no longer published. |

### Why drop GitHub Releases APKs

GPL-3 requires *source* availability, not *binary* availability. Pre-built APKs in Releases compete with the paid Play Store build for zero benefit — every download is a free copy. Dropping them is honest: source-builders can build, F-Droid users can install, Play Store users can pay.

The README needs a one-paragraph update directing users to Play Store (when live), F-Droid (when accepted), or self-build via the FOSS flavor.

### Existing dev-friend users

Two clean migration paths:

1. **Move them to the FOSS flavor.** They keep everything working, full access, no billing surface. Recommended.
2. **Issue Play Store promo codes** at launch. Play Console allows up to 500 free codes per IAP per quarter. Useful if you want them on the same flavor as paying users.

### iOS

`iosMain` is unaffected by the flavor split. iOS BillingClientPort actual remains a stub returning `Free` (or `Pro` if/when iOS becomes a focus and StoreKit lands).

## Removed in this release: in-app updater

Distributing Pro through Play Store + F-Droid eliminates the use case the in-app updater was built for (sideloading APKs from GitHub Releases). The entire `app.kofipod.update` package and its UI/DI bindings are deleted in pre-Slice-0 cleanup.

### Why delete (not flag-disable)

- Both real distribution channels handle updates natively. Play Store auto-updates apps; the F-Droid client polls the F-Droid repo daily and notifies users when a new tag is built. F-Droid considers in-app updaters a smell.
- Source builders update via `git pull`. They don't need a button.
- Dead code behind a flag rots — six months of stale lint warnings, broken iOS stubs, missing migrations. Git history preserves it if it's ever needed back.

### Files removed

- `commonMain/.../update/` (UpdateChecker, UpdateConfig, UpdateModels, VersionCompare, LocalApkPathStore — 5 files).
- `androidMain/.../update/` (UpdateChecker.android, UpdateInstaller, AndroidLocalApkPathStore — 3 files).
- `iosMain/.../update/UpdateChecker.ios.kt`.
- `data/repo/UpdateRepository.kt`.
- `ui/screens/settings/UpdateActionPort.kt` + `Android/IosUpdateActionPort` actuals.
- `androidUnitTest/.../UpdateRepositoryTest.kt`.
- DI bindings in `CommonModule.kt`, `AndroidModule.kt`, `IosPlatformModule.kt`.
- The Settings screen "Check for update" entry.
- The `files/updates/` runtime path (no longer written).

### What stays

- Version-display string in Settings (so users can confirm what they're running).
- The existing `version.properties` flow + signing config — those still feed Play Store / F-Droid builds, just no longer feed an in-app fetcher.

## Free vs Pro — the line

### Free (today's app, forever)

Everything currently shipping. Specifically:

- Podcast Index search, subscriptions, library, downloads, queue, scheduler.
- Google Auto Backup (data restore on new device).
- **BYOK Gemini AI: Summary, Mentioned, Discuss/Q&A** — stays free with the user's own key.
- Stats & Levels (the 6 stats + coffee tiers).
- Standard playback: variable speed, sleep timer, chapters, casting, share, per-show notification toggles.
- All bug fixes and core-app improvements.

**Nothing currently free becomes paid in this release or any future release.** This is a hard rule.

### AI tier policy

The mental model: **Free generates, Pro saves.**

- **Free generates** — Summary, Mentioned, and Discuss/Q&A all stay free under BYOK Gemini. The user pays Google directly for inference; Kofipod has no per-user inference cost.
- **Pro saves** — Snippets, Bookmarks, Transcript & summary search, and PKM exports are the persistence/capture/search/share layer that wraps the AI output. Pro buyers can export the free-tier `EpisodeAiSummary` rows to Readwise / Obsidian / Notion / Markdown — capturing the AI knowledge into their own systems.

Why AI does **not** move to Pro:

1. **Friction stacking.** BYOK already costs ~10 minutes of setup (Gemini key + Google Cloud billing). Stacking a $12.99 Pro purchase on top creates three onboarding gates; conversion goes to zero.
2. **The willingness-to-pay lever is exports, not generation.** Snipd's user research showed the podcast → Notion / Readwise / Obsidian pipeline is what users pay for. Pro already monetises that. Paywalling the AI itself would be double-charging for the same user-perceived value.
3. **Composability.** The "Free generates, Pro saves" pitch is one sentence. "Limited free AI + Pro AI quotas + Pro saves" is muddy.

### Pro (paid, one-time)

**v1.0 launch:**

1. Snippets (MP4 + MP3 export)
2. Bookmarks with notes
3. PKM export pipeline (Markdown, Obsidian, Readwise)
4. Transcript & summary search

**v1.1 free upgrade for Pro buyers:**

5. Silence Skip
6. Smart Playlists
7. PKM: Notion

### Pro entry points (only these)

- Settings → Kofipod Pro section.
- Player screen: Snip and Bookmark icon buttons. On Free, tapping either opens the Paywall sheet. Dismissing the sheet returns the user to the player with the buttons still visible (no hide-after-dismiss behaviour). Each tap reopens the sheet — there is no rate-limit, but also no nag because nothing else triggers it.
- Library: search bar shows a one-time "Search transcripts (Pro)" hint. Dismissible.

No interstitials. No "you've used X of Y free snips." No nag toasts. No banners on Stats or Detail screens.

## Feature behaviour

### F1. Snippets

- **Trigger surfaces:** Player Snip button; AirPod / wired-headset double-tap mapped through `MediaSession` custom command; long-press on the playback timeline.
- **Snip-last-60s** opens an editor with a draft anchored at `[currentPosition − 60_000ms, currentPosition]`.
- **Editor** lets the user trim start/end on a waveform, edit the title (default = chapter title or generated), edit caption text (default = transcript-derived line nearest start time), and choose format MP4 or MP3.
- **Render — MP4 path:** Media3 `Transformer` + `Composition` with `BitmapOverlay` (cover bg + per-frame waveform) and `TextOverlay` (burned-in karaoke caption). Output to `cacheDir/snippets/<id>.mp4`.
- **Render — MP3 path:** Media3 audio-only export of the trimmed segment with ID3 cover + title + comment metadata pointing back to the source episode URL.
- **Caption pipeline:** publisher transcript first; if absent, BYOK Gemini transcription via the existing `AudioUploadCoordinator`; if neither, ship without burned-in captions (still exports cover + waveform).
- **Persistence:** every snippet is saved at the moment of editor entry, so users can re-share or re-export later without re-rendering. Re-rendering happens only when format or trim changes.
- **Foreground service:** rendering runs as a one-shot foreground service (`mediaProcessing` foreground type on API 34+) to survive 20–60s renders.
- **Sharing:** completion triggers system share sheet with the rendered file. Share caption auto-prefills with `<episode title> — <show name>\n<podcast pod.link URL>` (editable). No Kofipod-controlled URL.

### F2. Bookmarks with notes

- Tap "Bookmark" button at any timestamp during playback. Optional one-line note.
- Bookmarks are independent of Snippets — a bookmark stores a timestamp + note, no audio.
- Listed per-episode in Episode Detail and aggregated in a new Bookmarks list.
- Tap a row to seek-or-play at the timestamp (reuse `EpisodeDetailViewModel.seekToChapter` semantics).

### F3. PKM export pipeline

The willingness-to-pay wedge — Snipd users specifically cite this as why they pay. Kofipod ships it without any backend.

**Destinations:**

| Kind | Auth | Storage | v1.0 / v1.1 |
|---|---|---|---|
| Markdown file | none | system share / SAF save | v1.0 |
| Obsidian vault folder | SAF persistent URI | dropped `.md` files into the vault folder | v1.0 |
| Readwise | OAuth (custom-tab redirect) | REST API POST | v1.0 |
| Notion | OAuth + database picker | REST API POST | v1.1 |

**Exportable units:** snippets, bookmarks, AI summaries (the existing free `EpisodeAiSummary` rows). Either individually (per-row export action sheet) or in bulk (per-podcast, per-date-range, "everything since last sync").

**Markdown format:** YAML frontmatter (`podcast`, `episode`, `episodeUrl`, `timestampMs`, `createdAt`, `kofipodId`) + body (timestamped quote / note / summary text + chapter context).

**Token storage:** OAuth tokens land in the existing `EncryptedSharedPreferences` "kofipod_secure" file via the `AndroidKeyVault` pattern from `AiConfigRepository`. Excluded from Auto Backup, same posture as the Gemini key.

**Idempotency:** `ExportLog` table tracks `(itemKind, itemId, destinationKind) → externalId`. Re-exports update rather than duplicate (Readwise/Notion both support upsert via external IDs; Obsidian re-exports overwrite the same `.md` filename).

**Background sync:** queued exports run via a new `PkmExportWorker` (WorkManager, network + battery constraints). Failures retry with backoff; persistent failures surface a chip on the Connections settings screen.

### F4. Transcript & summary search

- Search bar on the **Library** screen (top of screen, distinct from the bottom-nav Search destination, which finds *new* podcasts via Podcast Index).
- SQLite **FTS5** virtual table indexed over: cached transcript text (free, when fetched for the AI Summary feature), `EpisodeAiSummary.summary`, bookmark notes, snippet titles + caption overrides.
- Result types: episode (transcript hit), summary (AI summary hit), bookmark, snippet.
- Tap result → open source at timestamp where applicable.

### F5. Silence Skip (v1.1)

- Custom Media3 `AudioProcessor` in the playback pipeline. Detects RMS below configurable threshold for > N ms; time-compresses or skips.
- Per-app setting (off / mild / aggressive) + per-show override (extends existing per-show settings surface).
- Counter ("skipped 12m 30s today") feeds Stats screen as a new tile.

### F6. Smart Playlists (v1.1)

- User-defined saved filter definitions stored as a structured predicate JSON.
- Predicates: state (unplayed / in-progress / completed), duration range, podcast set, days-old, has-transcript, downloaded-only, has-snippets.
- Surface as virtual rows in Library alongside existing folders/lists.
- Predicate evaluator is pure Kotlin in `commonMain` for unit testability.

### F7. Pro entitlement

- `ProEntitlementRepository` exposes a single `StateFlow<ProEntitlement>` with values `Free`, `Pro(source = Individual | Family)`, or `Unknown` (during initial restore).
- `BillingClientPort` is `expect/actual` — Android actual wraps Google Play Billing v6+; iOS actual returns `Free` until iOS becomes a focus.
- `PaywallRouter` is a single-call entry point any UI can use to launch the Paywall sheet with a contextual trigger key (so we can attribute conversions per surface in future analytics).
- Restore Purchase: automatic on every app cold start; manual button in Settings.
- Entitlement is **not** included in Auto Backup. New-device restore goes through Play Billing.

## Screens — brief for Claude Design

**Visual treatment is Claude Design's call.** This section lists the jobs each screen does, key elements, and notable states. No layout, palette, or motion prescription.

### Design doc as source of truth — for new features only

The bundled `docs/kofipod-pro-ui-design.html` is the visual reference for every Pro feature listed below (Paywall sheet, Snippet editor, Bookmarks list, Library search, PKM Connections, Export action sheet, Smart Playlist editor) **and for the Pro-related sections of the modified screens** (the Settings → Kofipod Pro section, the Library "Bookmarks" / search entry points, the Episode Detail "Saved" section, the Player Snip + Bookmark icon buttons).

**Use it when:**
- Implementing any new screen or composable that the design doc covers.
- Touching a Pro-gated entry point on a modified screen — match the design.

**How to consult this reference (mandatory before implementing any Pro UI):**

The HTML is a self-contained JS bundle — opening the file as plain text shows only the loader scaffolding, not the actual screens. To see the design content you have to render it.

- **Human review:** `open -a "Google Chrome" docs/kofipod-pro-ui-design.html`. Wait for the "Unpacking..." indicator to disappear, then scroll. Each screen tile is labeled `N · Screen name · state` (e.g. `2 · Snippet editor · idle`).
- **AI agent runs:** before implementing any Pro UI, render the doc with Playwright/Chromium and screenshot every relevant labeled tile (every state of every screen the slice touches). Save screenshots under `/tmp/kofipod-design-<slug>.png` and reference those paths in the slice plan's task descriptions. The `seo-visual` agent and the `general-purpose` agent both have Playwright access.
- **Plans derived from this spec MUST include a step that captures the relevant design tiles** before starting implementation. A slice that hasn't visually verified its target screens against this reference is incomplete.

Treat captured screenshots like an API contract — every divergence (defer-to-later-slice, cosmetic-only, swapped-for-feasibility) needs a deliberate decision recorded in the slice plan, not silent omission. If the implementation stops short of design fidelity (e.g. waveform widget waiting on a primitive that ships in a later slice), the plan must say so explicitly and call out which design elements are deferred.

**Do NOT use it as a backlog of UI drift:**
- Existing free-tier surfaces that the design doc happens to depict (e.g. Now-playing transport controls, the Library tile grid, Stats tiles, Episode Detail tab strip styling, Settings rows unrelated to Pro/Connections) may have drifted from current production. **That drift is intentional out-of-scope for the Pro project.** Do not "fix" non-Pro UI to match the design doc as a side effect of implementing a Pro slice. If the design doc and live app disagree on a non-Pro surface, the live app wins.

When in doubt about whether a discrepancy is a "new Pro feature → match design" or "existing free UI drift → leave alone": Pro-gated entry points and net-new screens follow the design; everything else stays as-is. Open a follow-up if a non-Pro UI refresh is genuinely needed — don't fold it into a Pro slice.

### New screens

1. **Paywall sheet (modal bottom)** — Job: convert the user at the moment they tap a Pro feature.
   - Elements: feature list (v1.0 + v1.1 with one-line descriptions and small icons), two SKU CTAs ($12.99 / $19.99-Family), restore-purchase link, link to a static "what's free vs Pro" page (markdown rendered in-app), "Maybe later" dismiss.
   - States: idle, billing-flow-launching, error (billing unavailable / network).
   - Trigger: any Pro-gated tap.

2. **Snippet editor (full screen)** — Job: trim a snippet, choose format, kick off render.
   - Elements: waveform with start/end drag handles, scrubber + playhead, title field, caption field, format toggle (MP4 / MP3), Render & Share primary action, Cancel.
   - States: idle, rendering (with progress bar), render-complete (handing off to share sheet), error.

3. **Bookmarks list (full screen)** — Job: browse all bookmarks across the library.
   - Elements: search/filter bar (podcast filter chip, sort toggle), grouped list rows (podcast → episode → timestamp + note), tap-to-seek.
   - States: empty, loaded, filtered, search-active.

4. **Library search results** — Job: surface mixed-source matches.
   - Elements: result rows tagged by type (Episode / Summary / Bookmark / Snippet), excerpt with match highlight, timestamp jump-to where applicable.
   - States: empty (no matches), no-query, loaded.

5. **PKM Connections settings** — Job: manage destinations.
   - Elements: Connection rows (Markdown, Obsidian vault folder picker, Readwise account, Notion account), per-row status (connected / disconnected / error), auto-export toggle, "last sync" timestamp, disconnect button.
   - States: per-row idle, connecting (OAuth in flight), error, last-sync-failed banner.

6. **Export action sheet (bottom)** — Job: pick destinations for a single item or batch.
   - Elements: destination toggles (only enabled ones selectable), Confirm button, "Export X items" summary.
   - States: idle, in-progress (toast), result toast.

7. **Smart Playlist editor (v1.1, full screen)** — Job: build a predicate visually.
   - Elements: predicate chip rows (state, duration, podcasts, age, etc.), name field, live "matches X episodes" preview, Save / Cancel.

### Modified screens

- **Player** — add Snip and Bookmark icon buttons in the existing actions row. Pro-gated: first tap shows Paywall sheet.
- **Episode Detail** — add a per-episode "Saved" section below the existing tab strip (`Chapters · Summary · Mentioned · Discuss`) listing this episode's snippets + bookmarks. **Tab strip stays four max** — Saved is its own section, not a fifth tab. Each row has an inline export action.
- **Library** — add a top-of-screen search bar (separate from the bottom-nav Podcast Index Search). Add a Bookmarks entry-point row.
- **Settings** — add a Kofipod Pro section (status: Free / Pro / Family, restore link, what's-Pro link). Add a Connections section (gateway to PKM Connections settings).
- **Stats** — add a "Time skipped" tile (v1.1 only, after Silence Skip ships).

### Untouched

- Bottom nav structure (Library / Search / Downloads / Settings).
- Discover/Search (Podcast Index).
- AI Summary / Mentioned / Discuss panels — behaviour identical, but their content becomes exportable through Connections.
- Stats & Levels base layout (v1.0).

## Code architecture

### New packages under `app.kofipod`

- **`pro/`** — `ProEntitlementRepository`, `ProEntitlement` sealed type, `BillingClientPort` (expect/actual), `PaywallRouter`.
- **`snippets/`** — domain types, `SnippetRepository`, `SnippetComposerViewModel`, `SnippetExporter` (expect/actual; Android actual wraps Media3 Transformer; iOS stub).
- **`bookmarks/`** — `BookmarkRepository`, list and composer ViewModels.
- **`pkm/`** — `PkmConnectionRepository`, `MarkdownFormatter` (pure Kotlin in commonMain), per-destination adapters (`ReadwiseAdapter`, `NotionAdapter`, `ObsidianAdapter`), OAuth helpers in androidMain.
- **`search/`** — `LibrarySearchRepository`, FTS query builder.
- **`playback/silence/`** (v1.1) — `SilenceSkipAudioProcessor` (androidMain).
- **`playlists/`** (v1.1) — `SmartPlaylistRepository`, predicate evaluator (pure Kotlin, commonMain).

### Modified packages

- **`data/db/`** — new schemas (see "Schema additions"), version bumps across slices.
- **`di/CommonModule.kt`** — register new repositories as singletons; bind `ProEntitlementRepository` for use across paywalled VMs; new `viewModel { … }` factories. Several VMs grow to 5–7 deps; bump factories in lockstep per the existing CLAUDE.md rule.
- **`ui/nav/Route.kt`** — new routes:
  - `Route.Paywall(triggerKey: String)`
  - `Route.SnippetEditor(snippetId: String)`
  - `Route.Bookmarks`
  - `Route.LibrarySearch`
  - `Route.Connections`
  - `Route.SmartPlaylistEditor` (v1.1)
- **`playback/`** — extend `KofipodPlayer` with a custom `MediaSession` command for AirPod tap-to-snip.
- **`share/Sharer`** — extend with MIME-aware paths (`video/mp4`, `audio/mpeg`, `text/markdown`).
- **`background/`** — new `SnippetRenderWorker` (foreground when needed) and `PkmExportWorker` (background sync for queued exports).

### Schema additions (SQLDelight)

One migration file per slice (do **not** collapse into a single migration). Starting from current schema version **15**:

| Slice | File(s) added | Columns | Schema version after |
|---|---|---|---|
| 1 | `Bookmark.sq` | `id`, `episodeId` (FK), `podcastId` (FK), `timestampMs`, `note?`, `createdAt` | 16 |
| 2 | `LibrarySearchIndex.sq` | FTS5 virtual over transcript / summary / note / snippet-title | 17 |
| 3 | `Snippet.sq` | `id`, `episodeId` (FK), `podcastId` (FK), `startMs`, `endMs`, `title?`, `captionOverride?`, `createdAt`, `lastExportFormat?`, `lastExportPath?` | 18 |
| 5 | `PkmConnection.sq` + `ExportLog.sq` (single migration, both tables introduced together) | see below | 19 |
| 8 (v1.1) | `SmartPlaylist.sq` | `id`, `name`, `predicateJson`, `createdAt` | 20 |

`PkmConnection.sq`: `id`, `kind`, `tokenRef?`, `folderUri?`, `enabledAt`, `lastSyncAt?`.
`ExportLog.sq`: `(itemKind, itemId, destinationKind)` PK + `externalId?`, `exportedAt`, `status`.

v1.0 launches at schema version **19**. v1.1 ends at **20**.

Auto Backup rules updated to **include** Bookmark, Snippet, SmartPlaylist, ExportLog. PkmConnection is included but `tokenRef` payloads live in the already-excluded `kofipod_secure` EncryptedSharedPrefs.

### Detekt / multiplatform discipline

- Existing `com.google.android.*` ban already covers `com.android.billingclient.*`; add it explicitly to avoid future ambiguity.
- Existing `androidx.media3.*` ban already covers Transformer.
- Add: `androidx.documentfile.*` to the forbidden list (SAF helper, Android-only).
- Readwise and Notion clients use raw Ktor — no SDK deps that would break iOS compile.

### Testing

- **Unit (commonMain):** Markdown formatter, smart-playlist predicate evaluator, Pro entitlement state machine, FTS query builder, snippet timestamp math, OAuth state-param hygiene, Readwise/Notion request DTO encoding.
- **Unit (androidUnitTest):** BillingClient port stub, Transformer composition graph builder.
- **Compose UI tests:** paywall sheet (free → billing flow → success / error), snippet editor empty + populated, bookmarks list, export action sheet.
- **Paparazzi:** new primitive components (waveform handle, predicate chip) once visual treatment lands from Claude Design.
- **Manual emulator verification (Pixel 9a):** snippet MP4 render, AirPod tap-to-snip, OAuth flows for Readwise + Notion. Per CLAUDE.md, verify via `adb shell uiautomator dump`.

## Cross-cutting concerns

- **BYOK AI key state** is independent of Pro state. Disconnect-AI does not downgrade Pro; revoking Pro does not wipe the Gemini key.
- **License/SPDX header** on new source files: `// SPDX-License-Identifier: GPL-3.0-or-later`.
- **iOS compile gate** stays green: every new androidMain dependency has a stub or `expect`/`actual` pair.
- **Telemetry:** none. No analytics SDK is added. Conversion attribution by trigger key (`paywall_snip`, `paywall_bookmark`, etc.) is captured locally as a counter for the developer's own debug-build inspection only — never transmitted.

## Slice plan

Authored as guidance for the implementation plan agent. Actual ordering and granularity will be refined there.

| Slice        | Scope | Notes |
|--------------|---|---|
| **Pre-0**    | Cleanup | Delete `app.kofipod.update` package + UI + DI + tests (see "Removed in this release"). Self-contained commit; emulator gate before Slice 0 starts. |
| **0**        | Pro entitlement plumbing + flavor split | Add `play` / `foss` flavors. `BillingClientPort` expect/actual across `playAndroid` + `fossAndroid` source sets. `ProEntitlementRepository`, Paywall sheet, restore-purchase. Gates a single toy feature for end-to-end validation in both flavors. README update for new distribution policy. |
| **1**        | Bookmarks | Smallest real feature; exercises schema-bump + Pro-gate pattern. |
| **2**        | Library search (FTS5) | Small surface; lights up FTS index for later features. |
| **3**        | Snippets MVP — MP3 only | Editor, render, share. Proves foreground-service pattern without MP4 risk. |
| **4**        | Snippets MP4 (Media3 Transformer) | Highest engineering risk; gets its own slice. Cover bg + waveform overlay + caption overlay. |
| **5**        | PKM exports — Markdown | Universal, zero-auth. Establishes Markdown formatter contract. |
| **6**        | PKM exports — Obsidian + Readwise | SAF folder picker; Readwise OAuth. |
| **7**        | Smart Playlists | Predicate model + editor + virtual rows in Library. |
| **— launch v1.0 —** | | |
| **8** (v1.1) | Silence Skip | Media3 `AudioProcessor` + per-show override + Stats tile. |
| **9** (v1.1) | PKM — Notion | OAuth + database picker. |

## Out of scope (v1)

- Cross-device sync (deferred to a future Cloud subscription, separate spec).
- Hosted snippet web pages (`kofipod.app/c/<id>`) — same.
- Hosted AI / drop the BYOK requirement — same.
- Voice Boost / DSP enhancement — engineering risk too high for solo dev.
- Chapter editor — research showed near-zero willingness-to-pay.
- Stats Pro (year-in-review, hour-of-day heatmap, time-saved) — same; existing free Stats stays.
- Web/desktop companion — requires backend.
- Third-party podcast index switch — out of scope.

## Open questions deferred to implementation plan

- Exact AirPod tap-to-snip mapping on Android: `MediaSession` custom command vs `MediaSession.Callback.onMediaButtonEvent`. Spike in Slice 0.
- Readwise OAuth: official OAuth vs API token paste. Official OAuth requires a registered redirect URI; API token paste is simpler but slightly worse UX. Decide in Slice 6.
- Notion database schema: do we let users pick a database, or do we create a "Kofipod Snippets" database on first connect? Decide in Slice 9.
- ~~Family SKU sharing semantics: confirm Play Billing v6+ Family Sharing actually grants entitlement to family-group accounts on cold start (the v6 docs are ambiguous). Spike in Slice 0~~.

## Decision log

- **Subscription rejected** — market data shows backlash (Pocket Casts) and Snipd's $9.99/mo is contested. One-time $12.99 lands inside the user-validated range and avoids the renewal trap.
- **Snippets feature included as headline** despite weak willingness-to-pay in competitor research, because (a) Media3 Transformer makes MP4 cheap, (b) it's the visible artifact that drives PKM exports, and (c) AntennaPod doesn't have it — first OSS Android podcast app to ship clip export.
- **Stats Pro and Chapter Editor dropped** — competitor research showed near-zero organic willingness-to-pay across Castro/Overcast/Pocket Casts/Snipd users.
- **MP4 chosen over MP3-only for v1** — research showed native MP4 outperforms hosted-page links 3–10× on social, and even MP3 in iMessage shows as an ugly file row vs MP4's inline preview. Media3 Transformer reduces the engineering risk that originally argued for MP3-first.
- **No license-server check** — GPL-3.0 means anyone can build Pro from source. Most users will not. The Play Store binary is the revenue surface; OSS purity stays intact.
- **Free trial rejected** — Play Billing IAP doesn't natively support trials for non-consumables; hand-rolled trials are fragile. If conversion underperforms, ship a launch discount instead.
- **Two product flavors (`play` / `foss`)** chosen over single-binary-with-runtime-check. `foss` flavor excludes Play Billing entirely so F-Droid will accept it, and source-builders get full Pro features unconditionally. The `play` flavor is the revenue product. AntennaPod / Bitwarden / Signal use this same shape successfully.
- **GitHub Releases APKs discontinued** — pre-built APKs were competing with the paid Play Store build. GPL-3 only requires source availability; binary availability is a project choice, not an obligation.
- **AI features stay free under BYOK** — moving them to Pro would stack three onboarding gates (Pro IAP + Gemini key + Google Cloud billing) and double-charge for the Pro export pipeline that already monetises AI output. "Free generates, Pro saves" is the cleaner mental model.
- **In-app updater removed** — distributing through Play Store + F-Droid means both real channels handle updates natively. The updater served only sideload-from-GitHub-Releases users, who no longer exist as a category. Deleted rather than flag-disabled to avoid dead-code rot.
