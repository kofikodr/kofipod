# Kofipod — Personal Podcast App (Design Spec)

**Date:** 2026-04-18
**Status:** Approved, ready for implementation planning
**License:** GPL-3.0-or-later

## 1. Overview

Kofipod is a personal podcasting app for Android, built as a Kotlin Multiplatform (KMP) project with Compose Multiplatform UI so an iOS build can follow later. It uses the free [Podcast Index](https://podcastindex.org) API via the [mr3y PodcastIndex-SDK](https://github.com/mr3y-the-programmer/PodcastIndex-SDK) (ktor3 variant). Users can search podcasts, organise them into folder-style lists, play episodes with full background playback, download episodes (manually or auto, per-podcast), receive daily notifications when new episodes appear, and back up their library to Google Drive's hidden `appDataFolder`.

Visual design is taken from the provided design bundle (`Kofipod Design.html`): purple-dominant light theme, muted-dark night theme, pink as a reserved CTA/accent colour, Plus Jakarta Sans + JetBrains Mono typography, eight top-level screens.

## 2. Scope

**In scope**
- Android phone app (min SDK 26, target SDK 35), distributed via Google Play (App Bundle).
- KMP/Compose MP project with `commonMain`, `androidMain`, scaffolded `iosMain`.
- Search (by title and by person), podcast detail, folder-style library management.
- Full audio playback (Media3 ExoPlayer), background playback, media session, lock-screen controls, speed, skip, resume position, sleep timer, mini-player.
- Manual downloads and per-podcast auto-downloads on a user-configurable storage cap (Wi-Fi + charging defaults).
- Daily (inaccurate, power-saving) episode check via WorkManager with system notifications.
- Google Sign-In and backup/restore of library metadata to Google Drive `appDataFolder`.
- Build-time injection of `PODCAST_INDEX_KEY` / `PODCAST_INDEX_SECRET`.
- Compose UI tests and Paparazzi screenshot tests (light + dark) for designed screens.

**Out of scope**
- Music, live streams, soundbites (filtered out of search results).
- Play Store Auto Backup (decision: Drive-only).
- iOS implementation beyond project scaffolding and `expect`/`actual` stubs.
- Podcast creation/publishing features.
- Unit/integration tests and Android instrumentation tests (not in initial scope; can be added later).

## 3. Architecture

### 3.1 Module & source-set layout

```
kofipod/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/     # UI, domain, repositories, DB, API, nav, theme
│   │   ├── commonTest/     # Compose UI tests
│   │   ├── androidMain/    # ExoPlayer/Media3, WorkManager, Google Sign-In,
│   │   │                   # Drive REST, notifications, DownloadService
│   │   ├── androidUnitTest/# Paparazzi screenshot tests
│   │   └── iosMain/        # AVPlayer / BGTaskScheduler stubs (expect/actual)
│   └── build.gradle.kts
├── gradle/libs.versions.toml
├── local.properties.template
├── keystore.properties.template
├── LICENSE                  # GPL-3.0-or-later
└── README.md
```

Single module keeps the project easy to reason about; split later if files in any source set grow beyond clarity.

### 3.2 Library choices (pinned in the Gradle version catalog)

| Concern | Choice | Rationale |
|---|---|---|
| UI | Compose Multiplatform + Material 3 | User accepted shared UI across Android + future iOS. |
| Navigation | Jetpack Navigation Compose (KMP) | First-party, now multiplatform. |
| DI | Koin | Works on all KMP targets without codegen. |
| Network | Ktor 3 | Required by `podcastindex-sdk-ktor3`. |
| Podcast Index | `io.github.mr3y-the-programmer:podcastindex-sdk-ktor3:0.4.0` | Handles HMAC auth and endpoints. |
| Image loading | Coil 3 | KMP, integrates with Compose. |
| Serialization | kotlinx.serialization | Backup blobs + API response wrappers. |
| DB | SQLDelight | KMP, compile-time-checked SQL. |
| Audio (Android) | AndroidX Media3 (ExoPlayer + MediaSessionService) | Standard Android choice, background-capable. |
| Audio (iOS stub) | `expect`/`actual` Player; AVPlayer actual scaffolded | Keeps common domain playback-agnostic. |
| Background check | WorkManager `PeriodicWorkRequest` (~24h; UNMETERED + `requiresCharging`) | Battery-aware, inaccurate by design. |
| Auth | Credential Manager + Google Identity Services | Modern sign-in API. |
| Drive | Google Drive REST v3 over Ktor, `drive.appdata` scope | Hidden per-app folder, zero quota cost. |
| Build-time config | BuildKonfig Gradle plugin + Android BuildConfig | Exposes keys to `commonMain`. |
| Screenshot tests | Paparazzi (JVM, androidUnitTest) | Fast, no emulator. |

### 3.3 Layering

```
ui (composable screens)
  └─ viewmodel (Compose Multiplatform ViewModel per screen)
       └─ repository (domain-facing Flow APIs)
            ├─ podcastindex api client (SDK)
            ├─ sqldelight database
            ├─ platform services (expect/actual: player, downloader,
            │   scheduler, credentials, drive, notifications)
            └─ buildkonfig (PODCAST_INDEX_KEY, PODCAST_INDEX_SECRET)
```

- Screens observe repository `Flow`s; no direct DB or network access from UI.
- All platform-specific side effects (ExoPlayer, WorkManager, Drive REST) sit behind `expect`/`actual` interfaces defined in `commonMain`.
- Filtering rules (no music/live/soundbite) live in the repository, not the UI.

## 4. Domain model

SQLDelight tables (column names sketched; exact schema authored during implementation):

- `Podcast` — `id` (PodcastIndex feedId), `title`, `author`, `description`, `artworkUrl`, `feedUrl`, `listId` (FK nullable), `autoDownloadEnabled`, `lastCheckedAt`, `addedAt`.
- `PodcastList` — `id`, `name`, `position`, `createdAt`. Folders model: a podcast belongs to at most one list; absence = "Unfiled".
- `Episode` — `id` (PodcastIndex episodeId), `podcastId` (FK), `guid`, `title`, `description`, `publishedAt`, `durationSec`, `enclosureUrl`, `enclosureMimeType`, `fileSizeBytes`, `seasonNumber`, `episodeNumber`.
- `Download` — `episodeId` (PK, FK), `state` enum (Queued | Downloading | Completed | Failed | Paused), `localPath`, `downloadedBytes`, `totalBytes`, `source` enum (Auto | Manual), `startedAt`, `completedAt`, `errorMessage`.
- `PlaybackState` — `episodeId` (PK, FK), `positionMs`, `durationMs`, `completedAt`, `playbackSpeed`, `updatedAt`.
- `SyncMeta` — key/value store for `drive_backup_version`, `last_auto_download_run`, etc.

Deleting a podcast cascades to its episodes, downloads, and local files. Deleting a list moves its podcasts to Unfiled (does not delete them).

## 5. Key flows

### 5.1 Search
User enters a query; UI shows two tabs — **By title** / **By person**. Each dispatches to the repository (`searchByTerm`, `searchByPerson`), which filters `medium == music`, `live == true`, and anything from the soundbites endpoint. Results render as a card with artwork, title, author, and description. Tap → Podcast detail.

### 5.2 Podcast detail & library
Detail shows header (art, title, author, description), a "Save to list" CTA (picks an existing list or creates one), a per-podcast **auto-download** toggle, and the episode list. Saving inserts the podcast and first page of episodes. Library screen shows folders; drag-to-reorder; podcasts are grouped under their list with an Unfiled bucket at the bottom. Long-press → move between lists or delete.

### 5.3 Downloads
- **Manual:** Episode row menu → "Download." Enqueued in `Download` table as `Manual`.
- **Auto:** When the daily check finds a new episode for a podcast with `autoDownloadEnabled`, it's enqueued as `Auto` — subject to the user's storage cap and Wi-Fi/charging constraints. When the cap is hit, the oldest **auto-downloaded** episodes are evicted first (manual downloads are never auto-evicted). A persistent `DownloadService` (Android foreground service) drains the queue using resumable HTTP range requests (Ktor client); progress is written to the DB and observed by the Downloads screen.

### 5.4 Playback
An `expect class Player` in `commonMain` with `play(episode) / pause / seek / setSpeed / stop / stateFlow`. Android `actual` wraps ExoPlayer inside a `MediaSessionService` so playback survives app kill, populates the lock-screen/media notification, and handles Bluetooth and focus changes. Position is written to `PlaybackState` every 10s and on pause/stop. Mini-player bar appears above the bottom nav whenever a session is active; tap expands a full player sheet (scrubber, speed, skip forward/back with user-configurable durations defaulting to 30s/10s, sleep timer).

### 5.5 Daily episode check
`EpisodeCheckWorker` runs under a `PeriodicWorkRequest` with a ~24h interval and constraints: `NetworkType.UNMETERED` + `requiresCharging = true` (the design's "Wi-Fi + battery-aware" promise). Work:
1. For each podcast, fetch latest episodes, diff by `guid`, insert new rows.
2. If any podcast has `autoDownloadEnabled`, enqueue downloads for its new episodes (respecting cap + eviction).
3. Post a grouped system notification: *"N new episodes from M shows."* Expand actions: Play, Download, Dismiss. Tapping a notification opens the relevant detail screen.
4. Write a row to a `ScheduleRun` table (or reuse `SyncMeta`) so the Scheduler explainer can render the last-7-runs chart.

The scheduler explainer screen visualises these runs and explains that the system may skip runs when off-Wi-Fi or off-charger.

### 5.6 Backup & restore (Google Drive `appDataFolder`)
- Settings → "Back up now" serialises library + lists + playback state to a versioned JSON blob (`kofipod-backup-v1.json`) and uploads to `appDataFolder`. Blob contains no audio.
- Settings → "Restore" fetches the blob, validates version, merges into the local DB.
- On sign-in, if the remote blob's `drive_backup_version` is newer than local, offer a non-blocking "Restore available" row. Auto-apply is off by default.
- Sign-out clears the access token; local DB stays intact.

### 5.7 Onboarding & sign-in
First-launch shows the onboarding screen with a prominent "Sign in with Google" (Credential Manager), plus a "Skip for now" affordance — signing in is only required for Drive backup, not for using the app. Sign-in state is reflected in Settings.

## 6. UI & visual system

### 6.1 Screens (mapped 1:1 from `Kofipod Design.html`)

| # | Screen | Notes |
|---|---|---|
| 01 | Onboarding / Google sign-in | Hero, CTAs, skippable. |
| 02 | Search (title + person) | Tabs, result cards with art/title/author/description. |
| 03 | Podcast detail + episodes | Header, Save-to-list CTA, per-podcast auto-download toggle, episode list. |
| 04 | Library (folders) | List-grouped podcasts, drag reorder, Unfiled bucket. |
| 05 | Downloads manager | Queued / Downloading / Completed sections; progress + speed; swipe delete. |
| 06 | New-episode notification | Rendered as a system notification; detail screen is the deep-link target. |
| 07 | Settings | Sign-in state, backup/restore, daily-check toggle, Wi-Fi/charging toggles, storage cap slider (500 MB – 20 GB), theme (System/Light/Dark), skip durations, about/license. |
| 08 | Daily scheduler explainer | Last-7-runs chart + explanatory copy. |

**Additions not in the canvas** (required by full playback scope):
- Mini-player bar above bottom nav.
- Full player sheet (scrubber, speed selector, skip forward/back, sleep timer).

Both follow the same visual system (purple surface, pink reserved for primary play CTA, JetBrains Mono for timestamps).

### 6.2 Design tokens (from `tokens.jsx`)

Light:
- `bg #FBF8FF`, `bgSubtle #F3ECFF`, `surface #FFFFFF`, `surfaceAlt #F6F0FF`
- `border #E7DDFB`, `borderStrong #D5C4F4`
- `text #1A0B33`, `textSoft #50407A`, `textMute #8A7BB0`
- `purple #4B1E9E`, `purpleDeep #2E0D6E`, `purpleSoft #6D3BD2`, `purpleTint #EADFFC`
- `pink #FF2E9A`, `pinkSoft #FFD6EA`
- `success #10B981`, `warn #F59E0B`, `danger #E11D48`

Dark:
- `bg #0D0814`, `surface #1A1128`, `surfaceAlt #231636`
- `text #F2E9FF`, `textSoft #BCA7E0`, `textMute #7E6BA6`
- `purple #A881F5`, `purpleDeep #7C4DEB`, `purpleSoft #C4A6FF`, `purpleTint #2A1A4A`
- `pink #FF6BB5`, `pinkSoft #3A1930`

Radii: `xs 8 / sm 12 / md 16 / lg 20 / xl 28 / pill 999`.
Type: Plus Jakarta Sans (weights 400/500/700/800) for UI; JetBrains Mono for timestamps, durations, sizes.
Pink is reserved strictly for primary CTAs, active-state highlights, and the "new" badge.

### 6.3 Navigation

Bottom nav with four top-level destinations: **Search**, **Library**, **Downloads**, **Settings**. Onboarding is a pre-app flow; Podcast Detail, Scheduler Explainer, and Full Player Sheet are modal/pushed routes.

## 7. Configuration & secrets

- `PODCAST_INDEX_KEY` / `PODCAST_INDEX_SECRET` — read from `local.properties` (or `PODCAST_INDEX_KEY` / `PODCAST_INDEX_SECRET` env vars on CI). Surfaced to `commonMain` via the BuildKonfig plugin, and to Android via `BuildConfig`. **Never committed.**
- `local.properties.template` committed with placeholders and a comment linking to `https://api.podcastindex.org/`.
- Google OAuth client IDs for debug and release added via `google-services` or the newer Credential Manager configuration; README explains how to create the Google Cloud project, enable the Drive API with the `drive.appdata` scope, and register debug/release SHA-1 fingerprints.
- Release signing: `keystore/release.jks` (gitignored) plus `keystore.properties` (gitignored) with a committed `.template` counterpart.

## 8. Testing strategy

**Scope (per user decision): Compose UI tests + Paparazzi screenshot tests only.**

- **Compose UI tests** (`commonTest`, using Compose MP testing APIs):
  - Search: typing a query produces filtered results and hides music/live items.
  - Podcast detail: "Save to list" persists the podcast and updates Library.
  - Library: moving a podcast between lists updates its `listId`.
  - Settings: storage-cap slider binds to the repository; theme toggle switches palettes.
  - Mini-player: appears/disappears with playback state and responds to play/pause taps.
- **Paparazzi screenshot tests** (`androidUnitTest`):
  - All eight designed screens rendered in both light and dark themes.
  - Mini-player and full player sheet in both themes.
  - Empty states for Library and Downloads.

Tests follow the CLAUDE.md rules: behavioural names, real assertions, no internal-method mocks, no navigation-only tests.

## 9. Non-goals & explicit decisions

- **Backup: Drive `appDataFolder` only.** Android Auto Backup is not enabled; `android:allowBackup="false"`.
- **Lists: folders model.** A podcast belongs to at most one list. (Multiple-membership considered and rejected during brainstorming to match the design.)
- **Auto-downloads are per-podcast opt-in.** Daily check notifies for every new episode; only flagged podcasts auto-download. Default constraints: Wi-Fi + charging.
- **Music, live, and soundbite content filtered out** at the repository layer.
- **No in-app key entry.** `PODCAST_INDEX_KEY` is build-time only.
- **License: GPL-3.0-or-later**, `LICENSE` file at root, SPDX header on every new Kotlin file.

## 10. References

- Design bundle: `kofipod-design/kofipod/` (HTML/CSS/JSX mockup, tokens, chats).
- Podcast Index SDK: https://github.com/mr3y-the-programmer/PodcastIndex-SDK
- Podcast Index docs: https://podcastindex-org.github.io/docs-api/
- Compose Multiplatform: https://www.jetbrains.com/compose-multiplatform/
- AndroidX Media3: https://developer.android.com/media/media3
- Google Drive REST v3 (`appDataFolder`): https://developers.google.com/drive/api/guides/appdata
