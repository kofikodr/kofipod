# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kofipod — a personal podcasting app built with Kotlin Multiplatform + Compose Multiplatform. Android is the primary target; iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) compile but are not the focus. Single Gradle module: `:composeApp`. Package root: `com.kofikodr.kofipod`.

### License & open-source posture

Kofipod is **open source under GPL-3.0-or-later**. The public repository is at **https://github.com/kofikodr/kofipod**. New source files MUST start with `// SPDX-License-Identifier: GPL-3.0-or-later`.

GPL-3.0 is **strong copyleft**: any code linked into the app — including transitive dependencies — must be license-compatible with GPL-3.0. When adding a dependency, check its license:

- ✅ Compatible: GPL-3.0, LGPL-3.0, Apache-2.0, MIT, BSD (2/3-clause), MPL-2.0
- ❌ Incompatible: Apache-1.x, GPL-2.0-only (without "or later"), proprietary/commercial-only, anything with a "no commercial use" or "no derivatives" clause, SSPL, BUSL
- ⚠️ Case-by-case: anything not in the standard SPDX list — flag for human review before adding

Do NOT add proprietary, source-available-but-non-libre, or "all-rights-reserved" code anywhere in the repo. The `foss` flavor is shipped to F-Droid-style distribution channels that audit for libre purity, so the bar is the same across `commonMain`, `androidMain`, `androidPlay`, and `androidFoss`.

### Distribution flavors (`play` vs `foss`)

The app ships in two product flavors, both built from this repo (see `flavorDimensions += "distribution"` in `composeApp/build.gradle.kts`):

- **`play`** — Play Store revenue build. `applicationId = com.kofikodr.kofipod`, no suffix; this is what's uploaded to Play Console. Includes Play Billing (`androidPlay/`) for the Pro upgrade. App label: `Kofipod`.
- **`foss`** — libre build, intended for F-Droid / sideload distribution. `applicationId = com.kofikodr.kofipod.foss` (suffix lets play and foss installs coexist on the same device for verification). `versionName` carries `-foss`. **Pro is unconditionally unlocked** and Play Billing is excluded — `androidFoss/` provides a no-op `BillingClientPort`. App label: `Kofipod (FOSS)`.

Source-set rules:
- Play Billing imports (`com.android.billingclient.*`) are **forbidden in `commonMain` and `androidFoss`** — detekt enforces this. They live only in `androidPlay/`.
- Anything in `commonMain` or `androidMain` ships in **both** flavors, so it must be GPL-compatible and must not reach into Play Billing types directly.
- Flavor-specific actuals/bindings (e.g., `BillingClientPort`) follow the expect/actual or interface-+-DI-binding pattern and are wired in the flavor-specific source set.

When the user says "the app", default to discussing both flavors unless a Play-only feature (billing, Play Console listing) is in scope.

## Commands

All commands use the wrapper (`./gradlew`). Gradle is installed via SDKMAN (`~/.sdkman/candidates/gradle/current`) and is NOT on PATH unless sourced; the wrapper works without that.

- Debug APK: `./gradlew :composeApp:assembleDebug`
- Compile-only (fastest green check): `./gradlew :composeApp:compileDebugKotlinAndroid`
- Install to attached device/emulator: `./gradlew :composeApp:installDebug`
- Common unit tests (JVM): `./gradlew :composeApp:testDebugUnitTest`
- Single test class: `./gradlew :composeApp:testDebugUnitTest --tests "com.kofikodr.kofipod.screenshots.TokensSnapshots"`
- Paparazzi snapshot verify: `./gradlew :composeApp:verifyPaparazziDebug`
- Paparazzi record/update baselines: `./gradlew :composeApp:recordPaparazziDebug`
- iOS compile (frameworks only, from Mac): `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
- Lint / format: `./gradlew :composeApp:ktlintFormat :composeApp:detekt`
- Install git hooks (one-time per clone): `./gradlew installGitHooks` — points `core.hooksPath` at `scripts/git-hooks/`, which activates both `pre-commit` (runs `ktlintFormat` + `detekt` on every commit with staged `.kt`/`.kts` files) and `post-checkout` (seeds a newly added `git worktree` with `local.properties` copied from the primary worktree, so worktree builds get the Podcast Index dev key automatically).

Android SDK lives at `~/Library/Android/sdk/`; `adb`/`emulator` are at `~/Library/Android/sdk/platform-tools/adb` and `~/Library/Android/sdk/emulator/emulator` (not on PATH). Target AVD for verification: `Pixel_9a`.

## Secrets / BuildKonfig

`composeApp/build.gradle.kts` reads secrets through `readSecret()` (local.properties → env var → empty). `USER_AGENT` (+ version fields) go into shared `com.kofikodr.kofipod.config.BuildKonfig`; the account-bound secrets (`PODCAST_INDEX_KEY`, `PODCAST_INDEX_SECRET`, `REVIEWER_UNLOCK_HASH`, `SENTRY_DSN`, `APTABASE_APP_KEY`) are Android `BuildConfig` fields, not BuildKonfig.

- `PODCAST_INDEX_KEY`, `PODCAST_INDEX_SECRET` — required for Podcast Index API calls.
- `USER_AGENT` — hardcoded default.

**Podcast Index credentials are wired per-variant** in `applicationVariants.all` (not in the flavor blocks): the gitignored `local.properties` dev key feeds **debug builds only** (`playDebug` via `readSecret`). Release variants read **env vars exclusively** — the `local.properties` file is intentionally ignored for release so a dev key can never be baked into a distributable AAB. **FOSS** variants are always empty. This split is guarded by the `verifyPlayDebugIncludesPodcastIndexSecrets` / `verifyFossReleaseExcludesPodcastIndexSecrets` tasks.

A real Podcast Index **developer key lives in the gitignored `local.properties`** on this machine (both the primary checkout and each worktree), so debug builds hit the API out of the box. New git worktrees are seeded automatically by the `post-checkout` hook (see the `installGitHooks` command) — it copies `local.properties` from the primary worktree into any newly added one.

Copy `local.properties.template` and `keystore.properties.template` before first build. `local.properties`, `keystore.properties`, `*.jks`, and `keystore/` are gitignored.

## Backup

Two independent backup mechanisms run side by side:

**1. SAF backup (user-driven, primary).** Code under `com.kofikodr.kofipod.backup/` (commonMain) + `androidMain/.../backup/`. The user picks any folder via Storage Access Framework (Drive, Dropbox, local Files, etc.) in **Settings → Backup folder**. Once a folder is set, the section surfaces **Back up now** and **Restore from backup…** rows. The scheduler (`BackupScheduler`) registers a periodic 24h `BackupWorker` (charging + unmetered) that no-ops when no folder is set. Backup format: `.kpbak` (a zip with `manifest.json` + `kofipod.db`, MIME `application/x-kofipod-backup`). The manifest carries app version, DB schema version, timestamp, size, and SHA-256 of the DB bytes — restore validates the SHA and refuses schemas newer than the current build (`Manifest.kt` → `DB_SCHEMA_VERSION`). The DB file inside the zip is package-name-agnostic (just SQLite), so a `.kpbak` is portable across `applicationId` changes — that's how the `app.kofipod` → `com.kofikodr.kofipod` rename migrated user data without `adb`. SAF backups include only the SQLDelight DB; SharedPreferences are not in scope (encrypted Gemini key + local pointers stay device-bound).

**2. Android Auto Backup (Google's transparent fallback).** Configured by `composeApp/src/androidMain/res/xml/backup_rules.xml` (API 31+) and `backup_rules_legacy.xml` (API 23–30). Uploads silently to the user's Google account, doesn't count against Drive quota, survives full device wipes / new-device setup, runs on Google's schedule (~daily, charging + Wi-Fi + idle). The whole `database` and `sharedpref` domains are included; `kofipod_secure.xml` (encrypted Gemini key), `kofipod_local.xml` (device-local pointers), and `kofipod_entitlement.xml` (cached Pro entitlement) are explicitly excluded. Audio downloads under `files/downloads/`, the streaming cache under `cache/media/`, and the updater APK under `files/updates/` live outside any included domain so Auto Backup skips them by default.

Auto Backup operates at the **file/domain level**, not the table level — there is no way to exclude a single SQLite table while keeping the rest of the DB. So every table inside `KofipodDatabase` rides along, including `EpisodeAiSummary`, `DiscussSession`, `DiscussMessage`, and `AudioUploadCache`. That's intentional and fine (they're small and non-sensitive); just know that to keep something out of backup you must either move it into its own file/domain or wipe it via a `BackupAgent` callback.

Per-app Auto Backup cap is **25 MB compressed**; growth-watch tables are `Episode` (one row per episode across every subscribed show — easily the largest), `DiscussMessage`, `ListeningSession`, and `EpisodeAiSummary`. There is no in-app sign-in, no OAuth client to maintain, and no `GOOGLE_SERVER_CLIENT_ID`.

**When to use which.** Auto Backup is the safety net (zero user effort, package-bound, can't be migrated across `applicationId`). SAF backup is the explicit/portable path: surfaces a "Back up now" button, restore is user-confirmed (`AlertDialog` warns "Replace all data?" before restore), and the resulting file is portable to any device or new applicationId. Both mechanisms read the same source-of-truth DB; they don't coordinate.

## Architecture

### Source sets

- `commonMain/kotlin/com/kofikodr/kofipod` — all shared logic and UI.
- `androidMain` — Android actuals: `DatabaseFactory`, `KofipodPlayer` (Media3), download/foreground services, notification permission composable.
- `iosMain` — iOS actuals (some features stubbed as TODO; iOS is secondary).
- `commonTest` — Compose UI tests.
- `test` — Paparazzi JVM snapshot tests (Android-variant baselines live in `composeApp/src/test/snapshots/images/`).

### Layered packages under `com.kofikodr.kofipod`

- `ui/` — Compose screens (`ui/screens/{search,library,detail,downloads,settings,player,scheduler}`), shared `primitives/`, `theme/` (tokens + `KofipodTheme`), `nav/` (typed `Route` sealed class used with Navigation Compose), `shell/AppShell.kt` (bottom nav chrome), `player/`, `permission/`.
- `data/` — `api/` (Podcast Index wrapper via `podcastindex-sdk`), `db/` (SQLDelight `DatabaseFactory` expect/actual + `buildDatabase`), `net/buildHttpClient` (Ktor), `repo/` (repositories exposing Flows over DAOs and the API).
- `di/CommonModule.kt` — single Koin module. Repositories are singletons; screens get `ViewModel`s via Koin `viewModel { ... }` factories. A named `"appScope"` CoroutineScope (SupervisorJob + Default) is used for process-lifetime collectors like `DownloadRepository`; reuse it rather than creating new ones.
- `playback/` — `KofipodPlayer` expect class (Android actual wraps Media3 ExoPlayer; iOS nullary actual). Common code never constructs it, only resolves it via Koin.
- `downloads/` — download engine + foreground service glue (Android `foregroundServiceType="dataSync"`).
- `background/` — WorkManager periodic `EpisodeCheckWorker` (charging + unmetered, ~24h) that only counts shows where per-podcast notify is on.
- `auth/` — Credential Manager Google sign-in (Android only).
- `share/` — `Sharer` expect/actual (Android `ACTION_SEND`).
- `domain/` — plain data types crossing layers.

### Data / schema

SQLDelight database name: `KofipodDatabase`, package `com.kofikodr.kofipod.db`. Schema files under `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/`:

- Tables: `Podcast.sq`, `Episode.sq`, `EpisodeChapter.sq`, `EpisodeAiSummary.sq`, `PendingAiOperation.sq`, `AudioUploadCache.sq`, `DiscussSession.sq`, `DiscussMessage.sq`, `PodcastList.sq`, `Download.sq`, `PlaybackState.sq`, `RecentPodcastView.sq`, `SyncMeta.sq`.
- Migrations in `migrations/` — current schema version is **21** (= `max(N.sqm) + 1`, because migration `N.sqm` moves schema from N to N+1). Add a new `N.sqm` file rather than editing existing tables, then bump `DB_SCHEMA_VERSION` in `backup/Manifest.kt` in lockstep (drift is guarded by `ManifestTest.dbSchemaVersion_matchesGeneratedSchema`). Dev installs auto-migrate; if a migration ever fails on an emulator, uninstall and reinstall to rebuild from `Schema.create`.

### Navigation

`Route` sealed class (qualified-name keyed); `NavHost` start destination is `Route.Search`. Bottom nav order: Library / Search / Downloads / Settings. No onboarding or splash — first launch drops straight into the app.

### Lint & static analysis

- **ktlint** (via `org.jlleitschuh.gradle.ktlint`) formats Kotlin sources. Config lives in `.editorconfig` — notably, `@Composable`/`@Preview`/`@Test` functions may use PascalCase, `androidx.compose.foundation.layout.*` is an allowed wildcard, and the `filename` rule is off to accommodate the `.ios.kt` / `.android.kt` suffix convention.
- **detekt** (via `io.gitlab.arturbosch.detekt`) runs a narrow ruleset — only `style>ForbiddenImport` is enabled. Config: `config/detekt/detekt.yml`. The rule is scoped to `**/commonMain/**` and blocks imports that would break iOS compile: `java.*`, `javax.*`, `kotlin.jvm.*`, `androidx.lifecycle.viewmodel.compose.*`, `androidx.media3.*`, `androidx.work.*`, `androidx.credentials.*`, `com.google.android.*`. Put Android-only imports in `androidMain` instead. When adding a new Android-only artifact, consider adding it to the forbidden list too.
- Pre-commit hook (`scripts/git-hooks/pre-commit`) runs `ktlintFormat` + `detekt` on every commit that touches `.kt`/`.kts`. Files are re-staged after format so auto-fixes land in the commit. Known caveat: if a Kotlin file has both staged and unstaged edits, ktlint's format may pull the unstaged portion into the commit — stage cleanly first.

### iOS compile must stay green

Android is the priority, but all three iOS targets must keep compiling. `./gradlew :composeApp:compileKotlinIosSimulatorArm64` is the quickest check and should pass before any commit that touches `commonMain`, `iosMain`, or `build.gradle.kts`. Two failure modes to watch for:

- **Android-only deps in `commonMain`.** Everything in `commonMain` must resolve for every target. `androidx.*` artifacts without KMP klibs (e.g. `lifecycle-viewmodel-compose`, Media3, WorkManager, Credentials) belong in `androidMain`. AndroidX Lifecycle 2.8+ does publish KMP for `lifecycle-viewmodel`/`lifecycle-runtime`, but most AndroidX is still Android-only — when in doubt, put it in `androidMain`.
- **JVM-only APIs in `commonMain`.** No `java.*`, no `System.currentTimeMillis()`, no `java.io.File`. Use `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` for timestamps (already the convention across VMs) and `expect`/`actual` for anything platform-specific.

### Performance-sensitive invariants

The detail screen's episode list was tuned for scroll-during-playback. Do not merge the 500ms playback ticker back into `DetailUiState` — keep `playingEpisodeId` and `activePlayback` as separate `StateFlow`s so only the active row recomposes per tick. `EpisodeRowData` must stay free of `isActive`/`isPlaying`/`progress` to keep `remember`-stabilized row lists stable. `KPIcon` caches its `Path` via `remember(name, sizePx)` — preserve this when editing.

### Tablet / large-screen layout

The app branches on form factor at the shell layer. Spec + 10-phase plan: `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` and `docs/superpowers/plans/2026-05-11-tablet-phase-*.md`. Static mockup: `docs/kofipod-tablet-design.html`.

**Classifier (`ui/layout/TabletSize.kt`).** A single source of truth — the `TabletSize` enum (`Tablet8Port`, `Tablet8Land`, `Tablet10Port`, `Tablet10Land`) plus `RailMode` (`IconOnly`, `IconLabel`, `Expanded`). Phone is represented by `null`, not an enum value, so phone code paths stay free of tablet branching. Breakpoints (spec §2): width `< 600.dp` → phone; otherwise the smaller dimension `< 900.dp` → 8", `>= 900.dp` → 10"; landscape iff `width > height`; the 8"/10" boundary is derived from canonical pairs (800 × 1200, 1000 × 1400). `WithTabletSize` wraps the app root in a `BoxWithConstraints` and pushes the classification into `LocalTabletSize`; consumers read it via `rememberTabletSize()`. KMP-friendly by design — no `LocalConfiguration`, no `material3-adaptive` artifact.

**Shell branch (`ui/shell/KofipodScaffold.kt`).** `AppShell` always wraps in `WithTabletSize`, then `KofipodScaffold` picks `PhoneScaffold` (Material `Scaffold` + bottom `MiniPlayer` + `BottomNav`) when `LocalTabletSize.current == null`, else `TabletScaffold` (leading `KofipodNavigationRail` + content column with a foot-anchored `DockedMiniPlayer`). Player route hides both rail and docked mini-player via the same asymmetric fade as the phone path (`enter = fadeIn(220ms)`, `exit = ExitTransition.None`) so the chrome flip during nav transitions isn't a single-frame flash. The stateless `TabletScaffoldContent` body is `internal` so Paparazzi can snapshot the Row(rail, Column(content, mini)) geometry without going through Koin or the real `KofipodPlayer` (whose Android actual eagerly builds a `MediaController` in `init`, which crashes inside Paparazzi).

**Bottom nav vs rail are NOT 1:1.** Phone bottom nav has 4 tabs: Library / Search / Downloads / Settings. Tablet rail (`ui/shell/KofipodNavigationRail.kt`) has 5: Library / Search / Downloads / **Stats** / Settings — Stats gets a top-level destination on tablet because there's room for it. If you add or remove a top-level destination, update both `AppShell.TABS` and the rail's destination list, and bump the rail Paparazzi baselines.

**Master-detail (`ui/layout/MasterDetailPane.kt`).** Two-pane container used by landscape tablet screens (Library, Search, Podcast detail, Episode detail). Default `masterWeight = 0.62f`, hairline divider, optional `emptyDetail` slot — `EmptyDetailHint` is the standard muted-text affordance. The decision of "master pane only" vs "master + detail" comes from `TabletSize.isMasterDetail` (true for `Tablet8Land` and `Tablet10Land`). When extending, do not duplicate the weight constant — read it from the spec or pass it via param.

**Size-aware tap routing.** Library, Search, and Episode-Detail tile/result taps don't all navigate; tablet landscape preview-first. The decision is centralised in three pure helpers — `ui/screens/library/PodcastTapRouting.kt`, `ui/screens/search/SearchResultTapRouting.kt`, `ui/screens/detail/EpisodeTapRouting.kt` — each returning a sealed `Navigate(id)` / `Select(id)` outcome based on `TabletSize?`. Phone + tablet portrait → `Navigate`; tablet landscape → `Select` (the detail pane's "Open" CTA commits the navigation). When wiring a new master-detail surface, add a routing helper rather than branching inline at each call site — every existing one has a `commonTest` companion (`*TapRoutingTest.kt`) and that's the cheapest place to lock the decision matrix.

**Per-screen pattern.** Tablet-specific composables either live alongside the phone implementation (size-aware width caps and grid columns, e.g. `StatsScreen`, `DownloadsScreen`, `PlayerScreen`) or split into a sibling file when the layout diverges substantially — `LibraryScreenTablet.kt` is the precedent. Episode-detail tablet landscape uses `MasterDetailPane` to host its own preview pane (`PodcastDetailViewModel` gained the `selectedEpisodeId` state for this — that's the 13-line VM growth in the merge).

**Docked mini-player (`ui/player/DockedMiniPlayer.kt`).** Tablet equivalent of the phone `MiniPlayer`. Reads `LocalTabletSize` to pick its size-aware paddings/artwork — keep `formatMs` / `Speed` helpers shared rather than duplicating per layout.

**Paparazzi coverage.** Tablet baselines exist for every classification × screen combo that ships (`Tablet8Port`, `Tablet8Land`, `Tablet10Port`, `Tablet10Land` plus the `phone_light` control). Snapshot files live under `composeApp/src/test/snapshots/images/` and follow the convention `com.kofikodr.kofipod.screenshots_<Class>_<state>_<size>_light.png`. Run `./gradlew :composeApp:verifyPaparazziDebug` after any change that touches tablet layout primitives, the rail, the scaffold, or any of the tablet-aware screens; re-record with `recordPaparazziDebug` only after eyeballing the diff. The Paparazzi `DeviceConfig` for tablets uses a specific density (`mdpi` family) so the snapshot resolves at the spec's 1400 × 1000 dp — don't change it without updating every tablet baseline.

**Detekt forbidden imports still apply.** `material3-adaptive` and `LocalConfiguration` are deliberately NOT used — the tablet branch was built on `BoxWithConstraints` so iOS stays green. Don't reach for the AndroidX adaptive artifact when extending tablet support; the existing `TabletSize` classifier is the seam.

### AI features

BYOK (bring-your-own-key) Gemini integration. Lives entirely in `com.kofikodr.kofipod.ai/`:

- `GeminiClient.kt` — Ktor wrapper over `generativelanguage.googleapis.com`. Pure HTTP shim: `validate`, `generateFromText`, the Files API primitives (`uploadAudio`, `pollUntilActive`, `generateFromAudio`, `deleteFile`), and the multi-turn `chat` surface. Structured-output decoding (`responseMimeType: application/json` + `responseSchema` → `AiSummaryJson` / `DiscussAnswerJson`) lives here too. Orchestration of the audio pipeline does **not** — `summariseAudio` was removed in favour of `AudioUploadCoordinator`.
- `AudioUploadCoordinator.kt` — owns "give me a Gemini Files API URI for this episode's audio." Both `AiSummaryRepository` and `DiscussRepository` go through `acquire(...)`, which checks the shared `AudioUploadCache` table and either reuses a non-expired URI (within Gemini's 48h Files API TTL minus a 1h safety margin) or runs the upload via the `AudioUploader` seam. Per-episode `Mutex` collapses concurrent calls to one upload.
- `AiSummaryRepository.kt` — picks transcript path when `episode.transcriptUrl` is non-blank, audio fallback when the episode is downloaded, else surfaces `Idle(available = null)`. Single-flight per episodeId via a `Mutex`. Runs on the named `"appScope"` so navigation away mid-pipeline doesn't cancel the request. Audio path goes through `AudioUploadCoordinator` (upload-or-cache) → `AudioSummariser` (`generateFromAudio` over the resulting URI).
- `AiConfigRepository.kt` + `AndroidKeyVault.kt` — the user's Gemini key lives in `kofipod_secure` `EncryptedSharedPreferences`, **not** in BuildKonfig. That prefs file is excluded from Auto Backup (see `backup_rules.xml`) so the key is per-device by design — the user re-pastes it on a new install. (The cached-summary / discuss / upload-cache tables *do* ride along inside the backed-up DB file, since Auto Backup can't filter at the table level — see the Backup section above. That's accepted: they're small and non-sensitive.) The key never enters logs, the prompt body, or the response body — `GeminiClient` only logs operation names + status codes via the `Kofipod-AI` tag.
- `EpisodeAiSummary` table caches one row per episode: prose summary plus three JSON columns (`peopleJson`, `thingsJson`, `linksJson`) holding `[{name, subtitle}]` for people/things and `[{label, url}]` for links. Decoders fall back to an empty list on parse failure rather than tearing down the Ready card; the person/thing decoders also accept the legacy flat-string array shape so cached rows from before Slice 3.5 still render.

UI lives under `ui/screens/detail/ai/` (tab cards) and `ui/screens/askgemini/` (full-screen chat). The episode-detail tab strip order is `Chapters | Summary | Mentioned | Discuss` (Chapters only present when the episode has them). Summary is prose-only; Mentioned renders one filtered section at a time (People / Books·things / Links) with rows that tap through to a Google search (`googleSearchUrl(name, subtitle)`) — links open their actual URL. Discuss surfaces an idle suggestion + composer-stub (or "Continue your chat" card when messages exist); tapping anything navigates to the full-screen `AskGeminiScreen` where the real input + message thread live.

The Discuss / Q&A pipeline mirrors the Summary repo's shape:
- `DiscussRepository.kt` — single-flight per episodeId via `sendLock`; runs on `appScope` so navigation away mid-call doesn't cancel; tracks live `Job`s in `activeJobs` so `clearForEpisode(episodeId)` (the trashcan affordance) and `clearAll()` (Disconnect) can cancel + drain before wiping rows. Audio path goes through `AudioUploadCoordinator` for upload-or-cache, then `chat()` with `ChatContext.Audio`.
- `DiscussSource.kt` — strategy seam for resolving the textual / audio context one episode sends to Gemini. `TranscriptDiscussSource` (publisher transcript) and `AudioDiscussSource` (downloaded audio) ship together; a composite source binding in `CommonModule` picks transcript-first, audio-fallback otherwise. The `DiscussContext` sealed type carries either `Available(transcript)` or `AudioReady(localPath, mimeType, sizeBytes, fingerprint)`.
- `ChatContext` (sealed) parameterises `ChatSummariser.chat(...)`'s first user turn — `Transcript(text)` sends a text part, `Audio(fileUri, mimeType)` sends a `fileData` part referencing an already-uploaded Files API resource. Adding e.g. `Video(...)` is one new variant + one `when` branch.
- `DiscussSession` + `DiscussMessage` tables persist one open chat per episode (UNIQUE on `episodeId`). `citationsJson` carries `[{label, timestampMs}]`. Messages cascade with the session via FK.
- `GeminiClient.chat(...)` is the multi-turn surface; the `Content` DTO has an optional `role` field and `GenerateContentRequest` has an optional `systemInstruction` so the system prompt + citation rules don't have to live inside every user turn.
- History sent to the model is capped at the last 20 turns inside `DiscussRepository`; older messages stay in the DB but don't go to the model.
- Audio chats trigger a "long chat uses more quota" banner once the user reaches `AUDIO_TURN_WARNING_THRESHOLD = 5` user turns. Transcript chats never trigger it (replay is cheap).

**Resume markers + worker.** The `PendingAiOperation` table is generalised over `kind ∈ {'summary', 'discuss_upload'}` (composite PK on `(episodeId, kind)`). `AiSummaryWorker` is the single WorkManager consumer: it calls `AiSummaryRepository.resumePending()` (which re-fires Summary pipelines for `kind='summary'` rows) and then `DiscussRepository.cleanStaleDiscussUploads()` (which just deletes `kind='discuss_upload'` rows — Discuss recovery is intentionally user-driven via re-tap, not silent re-fire). The two consumers are wrapped in independent `runCatching` blocks so an exception from one doesn't skip the other.

**Disconnect** in Settings wipes the key, every cached summary, every cached chat, AND every cached Files API upload row by calling `config.disconnect()` → `summaries.clearAll()` → `discuss.clearAll()`. `summaries.clearAll()` cascades to `coordinator.clearAll()` so the shared upload cache is wiped exactly once. We do **not** call `GeminiClient.deleteFile` on Disconnect — the 48h Files API TTL handles server-side cleanup. All steps cancel in-flight pipelines so a late network completion can't write back against the just-cleared tables.

When extending the wire shape (Summary side: new entity field; Discuss side: new citation field), update the JSON DTO, the matching `*_RESPONSE_SCHEMA`, the prompt copy, the repo's encode/decode helpers, and the canary fixture together. Summary canary: `androidUnitTest/resources/ai/sample_response.json` + `AiSummaryJsonTest`. Discuss canary: `androidUnitTest/resources/ai/sample_discuss_response.json` + `DiscussWireTest`.

## Testing conventions

Testing scope per user lock-in: Compose UI tests (`commonTest`) + Paparazzi JVM screenshots (`test`). No unit/integration/instrumentation tests in the initial scope. Paparazzi coverage now spans primitives, tokens, every tablet-aware screen across the four `TabletSize` classifications, the navigation rail, and the tablet scaffold — see the "Tablet / large-screen layout" section above for the file/naming convention. Screen baselines that still need fakes for Koin deps are split by extracting a stateless `*Content` (e.g. `LibraryContent`, `SearchContent`, `TabletScaffoldContent`) that takes state as a parameter; follow that pattern when adding new screen-level snapshots.

When adding emulator-verified features, the expected workflow is: assemble debug → install → interact via `adb` (use `adb shell uiautomator dump /sdcard/view.xml && adb pull /sdcard/view.xml /tmp/` to get real element bounds; don't guess coordinates from screenshots).

**All tests must pass before declaring work done.** Run `./gradlew :composeApp:testDebugUnitTest` (and `:verifyPaparazziDebug` if visuals changed) as part of the green-check sequence alongside compile + ktlintFormat + detekt. Do not ignore failing tests — even ones that look unrelated to the current change. If a test is failing, fix it (or the code it covers); only skip with explicit user sign-off.

## Koin ViewModel factories

Any change that adds a dependency to a `ViewModel` constructor must also update the corresponding `viewModel { ... }` factory in `CommonModule.kt`. `PodcastDetailViewModel` has grown to 9 positional params — if you add another, bump the factory in lockstep. `AskGeminiViewModel` (the Discuss full-screen) takes 7 deps because citation taps replicate `EpisodeDetailViewModel.seekToChapter`'s seek-or-play logic; if Phase 2 lifts that into a shared helper, both VMs should call through it instead of duplicating params.

## Build targets / versions

- Kotlin Multiplatform, Compose Multiplatform, Compose Compiler (Kotlin plugin), AGP via `libs.versions.toml`.
- `compileSdk` 35, `minSdk` 26, `targetSdk` 35, JVM target 17, Java source/target 17.
- Release `isMinifyEnabled = false`. `packaging` strips common META-INF noise.

(License + flavor model: see "License & open-source posture" and "Distribution flavors" under the Project section above.)

## Specs / plans

Living design + implementation docs:

- `docs/superpowers/specs/2026-04-18-kofipod-design.md`
- `docs/superpowers/plans/2026-04-18-kofipod-implementation.md`

When resuming slice-based execution, `git log --oneline` shows per-slice commits.
