# Kofipod Diagnostics — Design Spec

**Date:** 2026-05-05
**Status:** Approved for plan-writing
**Owner:** Kofikodr

## Goals

- Receive crash reports with readable, deobfuscated stack traces from production users.
- Receive lightweight, anonymized usage signal (event counts only) to know which features are used.
- Default OFF. Explicit opt-in per-channel in Settings.
- No PII, no install IDs, no advertising IDs, no Google Play Services dependency.
- License-clean for a GPL-3 OSS app distributed via F-Droid.
- Operating cost in the single-digit USD/month, fixed.

## Non-goals

- Session replay, heatmaps, funnels, retention cohorts.
- User identification across devices.
- iOS coverage in this iteration (iOS actuals are no-op stubs).
- A "send a test crash" debug button (foot-gun; defer).
- Real-time alerting / paging on crashes (review on a cadence).

## Approach

Two-vendor split-stack:

- **Crashes → GlitchTip** (MIT, Sentry-wire-protocol-compatible) self-hosted on Railway.
- **Usage events → Aptabase** (AGPL-3) cloud free tier, migrate to self-host on Railway only if 20k events/mo cap is exceeded.

Single Settings section ("Privacy & Diagnostics") with two independent toggles. Each toggle has an inline disclosure of exactly what's sent.

## User-facing behavior

### Settings UI

New section in `SettingsScreen` titled **"Privacy & Diagnostics"**, placed below the existing "Storage" section and above "About".

```
Privacy & Diagnostics
─────────────────────
[ ] Send crash reports
    Help fix bugs by sharing anonymous crash details when the
    app crashes. No personal information.
    ▸ What's sent?

[ ] Share anonymous usage data
    Help prioritize features by sharing counts of how often
    they're used. No identifiers, no IP address stored.
    ▸ What's sent?

Read the privacy policy ›
```

Both switches default OFF. Tapping "What's sent?" expands an inline disclosure listing the literal payload fields. Tapping "Read the privacy policy" opens `https://github.com/<repo>/blob/master/docs/privacy.md` in a browser.

### First-launch behavior

No onboarding prompt. Toggles stay off until the user opts in. The app must remain fully functional with both toggles off.

### State persistence

Two booleans stored in `kofipod_secure` `EncryptedSharedPreferences` (the existing prefs file used by `AndroidKeyVault`). This file is excluded from Auto Backup, so toggles do **not** survive device migration — fail-safe (a new device starts with diagnostics off).

Keys: `diagnostics.crashes.enabled`, `diagnostics.usage.enabled`.

## Architecture

### New package `app.kofipod.diagnostics/`

Mirrors the structure of existing platform-bridging packages (`auth/`, `share/`, `playback/`).

```
diagnostics/
  DiagnosticsConfigRepository.kt        // commonMain: Flow<Boolean> toggles
  CrashReporter.kt                      // commonMain: expect class
  CrashReporter.android.kt              // androidMain: Sentry KMP SDK
  CrashReporter.ios.kt                  // iosMain: no-op stub
  Telemetry.kt                          // commonMain: expect class
  Telemetry.android.kt                  // androidMain: Aptabase SDK
  Telemetry.ios.kt                      // iosMain: no-op stub
  TelemetryEvent.kt                     // commonMain: sealed event vocabulary
  CrashReporterScrubber.kt              // commonMain: pure beforeSend logic
```

### `DiagnosticsConfigRepository`

```kotlin
interface DiagnosticsConfigRepository {
    val crashesEnabled: Flow<Boolean>
    val usageEnabled: Flow<Boolean>
    suspend fun setCrashesEnabled(enabled: Boolean)
    suspend fun setUsageEnabled(enabled: Boolean)
}
```

Android implementation wraps `EncryptedSharedPreferences` (same instance as `AndroidKeyVault`). Reads emit current value on subscription via a `MutableStateFlow` seeded from disk. Single Koin singleton.

### `CrashReporter` (expect/actual)

```kotlin
expect class CrashReporter {
    fun enable()
    fun disable()
    fun isEnabled(): Boolean
}
```

**Android actual** owns the Sentry KMP SDK lifecycle:

- `enable()` calls `Sentry.init { ... }` if not already initialized. Idempotent.
- `disable()` calls `Sentry.close()` and clears the SDK so no further events leave the device. Idempotent.
- Constructor does **not** initialize the SDK. The SDK is loaded lazily on first `enable()`.

Sentry init options:

| Option | Value | Why |
|---|---|---|
| `dsn` | `BuildKonfig.SENTRY_DSN` | GlitchTip endpoint |
| `release` | `BuildKonfig.VERSION_NAME` | tag releases for triage |
| `environment` | `"release"` or `"debug"` | filter dev noise |
| `sendDefaultPii` | `false` | no IPs, no usernames |
| `attachScreenshot` | `false` | privacy |
| `attachViewHierarchy` | `false` | privacy |
| `enableUserInteractionBreadcrumbs` | `false` | drops "user clicked button X" |
| `enableAutoSessionTracking` | `false` | no session counts |
| `beforeSend` | `CrashReporterScrubber::scrub` | redact URLs in breadcrumbs |
| `beforeBreadcrumb` | `CrashReporterScrubber::scrubBreadcrumb` | drop noisy categories |

The exact option names above match Sentry's Android SDK; the KMP SDK exposes equivalents but a few names may differ at the API surface. Implementation should map to whatever the pinned KMP SDK version exposes; the *intent* of each row is the contract.

If `BuildKonfig.SENTRY_DSN` is empty, `enable()` is a no-op. This is a defense-in-depth layer beyond the user toggle (e.g. F-Droid builds without the DSN can never report).

### `CrashReporterScrubber` (pure)

Pure Kotlin function in `commonMain`, fully unit-testable:

- Strip query strings and paths from any URL in `event.message`, `event.exceptions[*].value`, and breadcrumb `data`.
- Drop breadcrumbs with category `http` whose URL contains `gemini`, `googleapis`, or `podcastindex` (incidental keys / API tokens).
- Drop breadcrumbs with category `query` (SQL).
- Replace any string that matches an episode title or podcast feed URL with `[redacted]`. (Matching is best-effort; the scrubber does not have access to DB state — it pattern-matches on URL shape.)

### `Telemetry` (expect/actual)

```kotlin
expect class Telemetry {
    fun enable()
    fun disable()
    fun track(event: TelemetryEvent)
}
```

Android actual wraps Aptabase. Same lazy-init pattern as `CrashReporter`. `track()` is a no-op when disabled. No `userId`, ever.

If `BuildKonfig.APTABASE_APP_KEY` is empty, `enable()` is a no-op.

### `TelemetryEvent`

Sealed class with one subtype per event. The entire vocabulary lives in this file so every event the app can emit is grep-able and reviewable in a code review.

Initial vocabulary (v1):

```kotlin
sealed class TelemetryEvent(val name: String, val props: Map<String, String>) {
    object AppOpened : TelemetryEvent("app_opened", emptyMap())
    data class SearchPerformed(val source: SearchSource) :
        TelemetryEvent("search_performed", mapOf("source" to source.value))
    object EpisodeDownloaded : TelemetryEvent("episode_downloaded", emptyMap())
    object EpisodePlayed : TelemetryEvent("episode_played", emptyMap())
    data class AiSummaryGenerated(val path: AiPath) :
        TelemetryEvent("ai_summary_generated", mapOf("path" to path.value))
    data class AiDiscussMessageSent(val path: AiPath) :
        TelemetryEvent("ai_discuss_message_sent", mapOf("path" to path.value))
}

enum class SearchSource(val value: String) { TYPED("typed"), CATEGORY("category") }
enum class AiPath(val value: String) { TRANSCRIPT("transcript"), AUDIO("audio") }
```

**Property values are restricted to enum-derived strings.** No free-form input ever reaches Aptabase. New events require editing this file (and therefore a code review).

### Initialization & Koin wiring

`DiagnosticsConfigRepository`, `CrashReporter`, `Telemetry` are Koin singletons.

A single `DiagnosticsBootstrapper` collects the two toggle flows on `appScope` and calls `enable()` / `disable()` on the matching component when the toggle changes. Started from `KofipodApplication.onCreate` (Android) — kicked off after Koin is up and before any activity launches.

```kotlin
class DiagnosticsBootstrapper(
    private val config: DiagnosticsConfigRepository,
    private val crashes: CrashReporter,
    private val telemetry: Telemetry,
    private val appScope: CoroutineScope,
) {
    fun start() {
        config.crashesEnabled
            .onEach { if (it) crashes.enable() else crashes.disable() }
            .launchIn(appScope)
        config.usageEnabled
            .onEach { if (it) telemetry.enable() else telemetry.disable() }
            .launchIn(appScope)
    }
}
```

### Settings UI wiring

`SettingsViewModel` exposes two new `StateFlow<Boolean>` mirroring `DiagnosticsConfigRepository` and two action methods (`setCrashesEnabled`, `setUsageEnabled`). The new section in `SettingsScreen` consumes them.

The new "What's sent?" disclosures are static Compose content — no extra state.

### Detekt / ktlint considerations

The Sentry KMP SDK is published for Android + iOS targets, so its imports could technically live in `commonMain`. The codebase convention (Media3, WorkManager, Credentials) is to keep platform-touching SDKs in `androidMain` and bridge through `expect/actual`. We follow that convention here for consistency, even though Sentry KMP would not break iOS compile.

Add `io.sentry.*` and `com.aptabase.*` to the `style>ForbiddenImport` list in `config/detekt/detekt.yml` scoped to `**/commonMain/**`.

## Build configuration

### BuildKonfig additions

`composeApp/build.gradle.kts` extends `readSecret()` to read two new keys:

- `SENTRY_DSN` — GlitchTip DSN for the Kofipod project
- `APTABASE_APP_KEY` — Aptabase app key for Kofipod

Both default to empty string. `local.properties.template` documents both with placeholder values.

### Sentry Gradle plugin

`io.sentry:sentry-android-gradle-plugin` configured for **release variants only**. Uploads `mapping.txt` to GlitchTip on `assembleRelease` / `bundleRelease`. Requires a separate `SENTRY_AUTH_TOKEN` env var (CI secret, not in `BuildKonfig`).

Plugin is gated behind `if (BuildKonfig.SENTRY_DSN.isNotBlank())` to keep CI green for forks without secrets.

### Dependencies

```toml
[versions]
sentry-kmp = "0.27.0"     # pin minor, expect breaking changes
aptabase = "0.0.10"

[libraries]
sentry-kmp = { module = "io.sentry:sentry-kotlin-multiplatform", version.ref = "sentry-kmp" }
aptabase = { module = "com.aptabase:aptabase-kotlin", version.ref = "aptabase" }

[plugins]
sentry-android = { id = "io.sentry.android.gradle", version.ref = "sentry-kmp" }
```

## Hosting (Railway)

### GlitchTip stack

Single Railway project `kofipod-glitchtip`, four services from GlitchTip's official compose:

- `glitchtip` (Django web)
- `worker` (Celery)
- `postgres` (with persistent volume — backups our responsibility)
- `redis`

Configuration:

- `SECRET_KEY` — Railway-generated
- `DATABASE_URL`, `REDIS_URL` — Railway service references
- `DEFAULT_FROM_EMAIL` — admin@ contact
- `EMAIL_URL` — disabled (no SMTP). Account creation done via initial signup, no email-based flows used.
- `ENABLE_USER_REGISTRATION` — `false` after initial admin signup
- Event retention configured to 30 days (GlitchTip's retention env var; exact name per current docs)
- Custom domain `crash.kofipod.app` (or subdomain of choice)

**Backups:** weekly Postgres dump to S3 via Railway scheduled job. Crash data is non-precious — losing a week is fine.

**Estimated cost:** $5–8/mo on Railway's usage-based billing.

### Aptabase

Cloud free tier. Account on aptabase.com, app key copied into `local.properties`. Migrate to Railway self-host (Aptabase publishes a docker-compose) only if monthly events approach 20k.

## Privacy posture

### Data flowing to GlitchTip (when crash toggle is ON)

| Field | Source | Notes |
|---|---|---|
| Stack trace | Sentry SDK | deobfuscated server-side |
| Exception class & message | Sentry SDK | scrubbed of URLs |
| OS version | Sentry SDK | `Android 14` etc. |
| Device model | Sentry SDK | `Pixel 7` etc. — not unique |
| App version | `BuildKonfig.VERSION_NAME` | |
| Locale | Sentry SDK | `en-US` etc. |
| Breadcrumbs | filtered by scrubber | no `http`/`query`, no UI clicks |
| Release tag | `BuildKonfig.VERSION_NAME` | |

**Explicitly NOT sent:** IP address (`sendDefaultPii=false` + GlitchTip not configured to log), user ID (never set), screen contents, view hierarchy, breadcrumbs containing URLs to Gemini/PodcastIndex/Google.

### Data flowing to Aptabase (when usage toggle is ON)

| Field | Source | Notes |
|---|---|---|
| Event name | `TelemetryEvent.name` | enum-derived |
| Event props | `TelemetryEvent.props` | enum-derived strings only |
| App version | `BuildKonfig.VERSION_NAME` | |
| OS version | Aptabase SDK | |
| Locale | Aptabase SDK | |
| Hashed daily ID | Aptabase server | `SHA(IP + UA + daily_salt)` — rotates every 24h, never sees the app |

**Explicitly NOT sent:** any client-side identifier, any free-form string, any podcast/episode/feed/URL data.

### Privacy doc

New file `docs/privacy.md` committed to the repo, linked from the Settings screen. Lists the two tables above verbatim plus:

- The two toggles, default state, and where they're stored.
- The hosting model (GlitchTip on Railway, Aptabase cloud) so users can audit the network destinations.
- The data retention policy (30 days for crashes, 12 months for events — Aptabase default).
- A statement that turning off a toggle stops further sends but does **not** delete data already sent.

## Testing

### Unit tests (commonTest, JVM)

- `CrashReporterScrubberTest` — given a synthetic Sentry event with breadcrumbs containing Gemini URLs, query SQL, and an episode title, assert the scrubbed event has those redacted/dropped.
- `TelemetryEventTest` — round-trip every `TelemetryEvent` subtype through `name` and `props`, assert no value contains a non-enum string.
- `DiagnosticsConfigRepositoryTest` — toggle persistence, default-off behavior, flow emission.

### Integration tests (Compose UI)

- `SettingsDiagnosticsTest` — both switches default off, flipping persists across recomposition, "What's sent?" disclosure expands and shows expected text.

### Paparazzi snapshot

- New snapshot of the "Privacy & Diagnostics" section in both light and dark themes, both toggles off, then both toggles on with disclosures expanded.

### Manual verification

After implementation, on a debug build with DSN/key configured:

1. Toggle crashes ON, force a synthetic exception via a hidden long-press in the About screen (debug-only). Confirm event arrives in GlitchTip with deobfuscated stack.
2. Toggle usage ON, perform each `TelemetryEvent`, confirm events arrive in Aptabase dashboard.
3. Toggle both OFF, repeat 1+2, confirm nothing arrives over a 60-second window (use `mitmproxy` or Charles to verify no outbound requests to crash.kofipod.app or aptabase.com).
4. Test ProGuard rules on a release build by running it on the emulator and confirming the SDKs still init.

The hidden long-press from step 1 is debug-build-only and does not ship in release.

## Migration & rollout

- Initial DB migration **not** required — toggles live in EncryptedSharedPreferences, not SQLDelight.
- Schema version stays at 15.
- Feature is live-on-merge for users who manually opt in. No staged rollout needed.

## Open issues / deferred

- **iOS actuals.** Stubs only this iteration. When iOS becomes a target, Sentry KMP SDK already covers it; Aptabase ships a Swift SDK that will need a separate `iosMain` actual.
- **Aptabase self-host.** Defer until cloud free tier is exceeded. Sketch a follow-up plan when MAU justifies it.
- **GlitchTip backup automation.** Initial setup uses Railway's manual snapshots; automate weekly Postgres dump → S3 in a follow-up.
- **F-Droid build.** F-Droid's build is reproducible-from-source. Since DSN/key are read from `local.properties` (env in CI), F-Droid's builds will have empty values and both subsystems will be permanently disabled — exactly what F-Droid wants. No build flavor needed.
- **Crash review cadence.** Maintainer to check GlitchTip weekly. No automated paging.
