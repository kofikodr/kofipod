# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kofipod — a personal podcasting app built with Kotlin Multiplatform + Compose Multiplatform. Android is the primary target; iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) compile but are not the focus. Single Gradle module: `:composeApp`. Package root: `app.kofipod`.

## Commands

All commands use the wrapper (`./gradlew`). Gradle is installed via SDKMAN (`~/.sdkman/candidates/gradle/current`) and is NOT on PATH unless sourced; the wrapper works without that.

- Debug APK: `./gradlew :composeApp:assembleDebug`
- Compile-only (fastest green check): `./gradlew :composeApp:compileDebugKotlinAndroid`
- Install to attached device/emulator: `./gradlew :composeApp:installDebug`
- Common unit tests (JVM): `./gradlew :composeApp:testDebugUnitTest`
- Single test class: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.screenshots.TokensSnapshots"`
- Paparazzi snapshot verify: `./gradlew :composeApp:verifyPaparazziDebug`
- Paparazzi record/update baselines: `./gradlew :composeApp:recordPaparazziDebug`
- iOS compile (frameworks only, from Mac): `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
- Lint / format: `./gradlew :composeApp:ktlintFormat :composeApp:detekt`
- Install pre-commit hook (one-time per clone): `./gradlew installGitHooks` — points `core.hooksPath` at `scripts/git-hooks/`, so `scripts/git-hooks/pre-commit` runs `ktlintFormat` + `detekt` on every commit with staged `.kt`/`.kts` files.

Android SDK lives at `~/Library/Android/sdk/`; `adb`/`emulator` are at `~/Library/Android/sdk/platform-tools/adb` and `~/Library/Android/sdk/emulator/emulator` (not on PATH). Target AVD for verification: `Pixel_9a`.

## Secrets / BuildKonfig

`composeApp/build.gradle.kts` reads four values through `readSecret()` (local.properties → env var → empty) and exposes them via `app.kofipod.config.BuildKonfig`:

- `PODCAST_INDEX_KEY`, `PODCAST_INDEX_SECRET` — required for Podcast Index API calls.
- `USER_AGENT` — hardcoded default.
- `GOOGLE_SERVER_CLIENT_ID` — OAuth Web client ID for Credential Manager sign-in; when empty, sign-in surfaces `SignInError.NotConfigured` and the UI hint says "Add GOOGLE_SERVER_CLIENT_ID to local.properties".

Copy `local.properties.template` and `keystore.properties.template` before first build. `local.properties`, `keystore.properties`, `*.jks`, and `keystore/` are gitignored.

## Architecture

### Source sets

- `commonMain/kotlin/app/kofipod` — all shared logic and UI.
- `androidMain` — Android actuals: `DatabaseFactory`, `KofipodPlayer` (Media3), download/foreground services, Credential Manager sign-in, notification permission composable.
- `iosMain` — iOS actuals (some features stubbed as TODO; iOS is secondary).
- `commonTest` — Compose UI tests.
- `test` — Paparazzi JVM snapshot tests (Android-variant baselines live in `composeApp/src/test/snapshots/images/`).

### Layered packages under `app.kofipod`

- `ui/` — Compose screens (`ui/screens/{search,library,detail,downloads,settings,player,scheduler,splash,onboarding}`), shared `primitives/`, `theme/` (tokens + `KofipodTheme`), `nav/` (typed `Route` sealed class used with Navigation Compose), `shell/AppShell.kt` (bottom nav chrome; suppresses bar on Splash/Onboarding), `player/`, `permission/`.
- `data/` — `api/` (Podcast Index wrapper via `podcastindex-sdk`), `db/` (SQLDelight `DatabaseFactory` expect/actual + `buildDatabase`), `net/buildHttpClient` (Ktor), `repo/` (repositories exposing Flows over DAOs and the API).
- `di/CommonModule.kt` — single Koin module. Repositories are singletons; screens get `ViewModel`s via Koin `viewModel { ... }` factories. A named `"appScope"` CoroutineScope (SupervisorJob + Default) is used for process-lifetime collectors like `DownloadRepository`; reuse it rather than creating new ones.
- `playback/` — `KofipodPlayer` expect class (Android actual wraps Media3 ExoPlayer; iOS nullary actual). Common code never constructs it, only resolves it via Koin.
- `downloads/` — download engine + foreground service glue (Android `foregroundServiceType="dataSync"`).
- `background/` — WorkManager periodic `EpisodeCheckWorker` (charging + unmetered, ~24h) that only counts shows where per-podcast notify is on.
- `auth/` — Credential Manager Google sign-in (Android only).
- `share/` — `Sharer` expect/actual (Android `ACTION_SEND`).
- `domain/` — plain data types crossing layers.

### Data / schema

SQLDelight database name: `KofipodDatabase`, package `app.kofipod.db`. Schema files under `composeApp/src/commonMain/sqldelight/app/kofipod/db/`:

- Tables: `Podcast.sq`, `Episode.sq`, `PodcastList.sq`, `Download.sq`, `PlaybackState.sq`, `RecentPodcastView.sq`, `SyncMeta.sq`.
- Migrations in `migrations/` — current schema version is **3**. Add a new `N.sqm` file rather than editing existing tables. Dev installs auto-migrate; if a migration ever fails on an emulator, uninstall and reinstall to rebuild from `Schema.create`.

### Navigation

`Route` sealed class (qualified-name keyed); `NavHost` start destination is `Route.Splash`. Splash delays 1500ms then routes based on `SettingsRepository.onboardedNow()` (a sync probe — do not use in hot paths). Bottom nav order: Library / Search / Downloads / Settings. Onboarding is skippable; sign-in is opt-in behind the Settings "Back up to Google Drive" switch.

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

## Testing conventions

Testing scope per user lock-in: Compose UI tests (`commonTest`) + Paparazzi JVM screenshots (`test`). No unit/integration/instrumentation tests in the initial scope. Paparazzi baselines currently cover primitives + tokens only (4 baselines); screen-level baselines are intentionally deferred because they need fakes for Koin deps or a `*Content` split that takes state as a parameter.

When adding emulator-verified features, the expected workflow is: assemble debug → install → interact via `adb` (use `adb shell uiautomator dump /sdcard/view.xml && adb pull /sdcard/view.xml /tmp/` to get real element bounds; don't guess coordinates from screenshots).

## Koin ViewModel factories

Any change that adds a dependency to a `ViewModel` constructor must also update the corresponding `viewModel { ... }` factory in `CommonModule.kt`. `PodcastDetailViewModel` has grown to 9 positional params — if you add another, bump the factory in lockstep.

## Build targets / versions

- Kotlin Multiplatform, Compose Multiplatform, Compose Compiler (Kotlin plugin), AGP via `libs.versions.toml`.
- `compileSdk` 35, `minSdk` 26, `targetSdk` 35, JVM target 17, Java source/target 17.
- Release `isMinifyEnabled = false`. `packaging` strips common META-INF noise.
- License: GPL-3.0-or-later. New source files carry `// SPDX-License-Identifier: GPL-3.0-or-later` at the top.

## Specs / plans

Living design + implementation docs:

- `docs/superpowers/specs/2026-04-18-kofipod-design.md`
- `docs/superpowers/plans/2026-04-18-kofipod-implementation.md`

When resuming slice-based execution, `git log --oneline` shows per-slice commits.
