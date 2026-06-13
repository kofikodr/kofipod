# FOSS Podcast Index BYOK — Design

**Date:** 2026-06-13
**Status:** Approved (design), pending implementation plan
**Scope:** Add an in-app "bring your own key" (BYOK) path so FOSS-flavor users can supply their own Podcast Index API credentials, since PR #36 scoped the maintainer's build-time credentials to the Play flavor only.

## Problem

After PR #36, the `foss` product flavor bakes in empty Podcast Index credentials:

```kotlin
// composeApp/build.gradle.kts — foss flavor
buildConfigField("String", "PODCAST_INDEX_KEY", "\"\"")
buildConfigField("String", "PODCAST_INDEX_SECRET", "\"\"")
```

The `play` flavor reads real credentials via `readSecret(...)`. The Android actual
`PodcastIndexCredentials` reads these `BuildConfig` fields. Consequently **FOSS builds have no
working Podcast Index credentials and search is dead**, with no supported runtime path for a user
to supply their own. The existing in-flavor comment ("self-builders can inject their own credentials
in forks") is aspirational — the code provides no such mechanism.

A free Podcast Index API key + secret is available instantly from <https://podcastindex.org/api>.
This feature lets a FOSS user paste their own key/secret into the app.

## Goals

- FOSS users can enter their own Podcast Index `key` + `secret` in Settings and have search work
  **without restarting the app**.
- Credentials are stored encrypted on-device and never leave the device or sync to backup.
- Zero change to the Play flavor's behavior; Play users never see the field.
- The maintainer's credentials are never embedded in a FOSS APK (preserve PR #36's guarantee).
- iOS posture unchanged (stubbed, out of scope).

## Non-goals

- No build-time FOSS credential injection (a separate, already-rejected option).
- No change to how the Play flavor sources credentials.
- No caching/migration of existing search state on connect/disconnect (search results are not
  persistently cached the way AI summaries are).

## Visibility rule

The Settings entry point appears **iff the build-time credentials are blank**:

```kotlin
PodcastIndexCredentials.key.isBlank()
```

This is `true` for FOSS, `false` for Play — so it is automatically FOSS-only with **no
flavor-specific UI code**. If a FOSS user has entered BYOK credentials, the field still shows
(now in "Connected" state, allowing disconnect/replace).

## Architecture

Mirrors the existing Gemini BYOK pattern (`KeyVault` → `AiConfigRepository` → `AiSetupScreen`/VM →
DI → backup exclusion). Five new units plus DI and a client-wiring change.

### 1. Storage — `PodcastIndexCredentialStore`

New seam; does **not** modify Gemini's `KeyVault`.

```kotlin
// commonMain
data class PodcastIndexCreds(val key: String, val secret: String)

interface PodcastIndexCredentialStore {
    suspend fun get(): PodcastIndexCreds?   // null when neither field stored
    suspend fun set(creds: PodcastIndexCreds)
    suspend fun clear()
}
```

- **androidMain** `AndroidPodcastIndexCredentialStore(context)` reuses the existing
  **`kofipod_secure`** `EncryptedSharedPreferences` file (same `MasterKey` setup as
  `AndroidKeyVault`), with keys `podcast_index_key` and `podcast_index_secret`. Uses synchronous
  `commit()` and throws on failure (so the config repo does not flip its flag on a failed write).
  `get()` returns `null` unless **both** values are present and non-blank.
- Reusing `kofipod_secure` means **no new `backup_rules.xml` exclusion is required** — that file is
  already excluded from Auto Backup. (Confirm the legacy `backup_rules_legacy.xml` likewise excludes
  it; if not, extend it.)
- **iosMain** `IosPodcastIndexCredentialStoreStub` — no-op (`get()` → null), matching the Gemini iOS
  stub posture.

### 2. Config repository — `PodcastIndexConfigRepository`

Mirrors `AiConfigRepository`.

```kotlin
class PodcastIndexConfigRepository(
    private val store: PodcastIndexCredentialStore,
    appScope: CoroutineScope,
) {
    val isConfigured: StateFlow<Boolean>          // hydrated from store on startup (appScope.launch)
    suspend fun currentCreds(): PodcastIndexCreds? // one-shot read; never memoised in the field
    suspend fun setCredentials(creds: PodcastIndexCreds) // store.set then flip flag to true
    suspend fun disconnect()                       // store.clear then flip flag to false
}
```

- Startup hydration is single-flight and swallows store errors (treat as "not configured").
- The flag and the store stay in sync: if `store.set` throws, the flag does not flip.

### 3. Effective credentials + reactive client

Today `PodcastIndexApi.create()` reads `PodcastIndexCredentials.key/secret` once and builds a
`Ktor3PodcastIndexClient` whose credentials are fixed at construction. To make BYOK changes take
effect without a restart:

```kotlin
// commonMain — resolves which credentials are actually in effect
class EffectivePodcastIndexCredentials(
    private val config: PodcastIndexConfigRepository,
) {
    // BYOK creds when present and non-blank, else the build-time PodcastIndexCredentials.
    suspend fun resolve(): PodcastIndexCreds
}
```

```kotlin
// commonMain — PodcastIndexClient decorator that rebuilds the SDK client only when creds change
class ReconfigurablePodcastIndexClient(
    private val effective: EffectivePodcastIndexCredentials,
    private val build: (PodcastIndexCreds) -> PodcastIndexClient, // default: Ktor3PodcastIndexClient(...)
) : PodcastIndexClient {
    // On each delegated call: resolve effective creds; if they differ from the creds used to build
    // the current delegate, rebuild it; then delegate. Guarded by a Mutex so concurrent searches
    // don't race the rebuild. Caches the last-built (creds -> delegate) pair.
}
```

- `PodcastIndexApi` keeps its `class PodcastIndexApi(private val client: PodcastIndexClient)` shape.
  The Koin singleton is constructed from the reconfigurable client instead of `PodcastIndexApi.create()`.
- `PodcastIndexApi.create()` (the static factory) is removed in favor of DI wiring; its single caller
  is the Koin module.
- Rebuilds are rare (only when the user connects/disconnects), so the compare-and-rebuild has
  negligible cost and avoids per-request Ktor client churn.

### 4. Validation — `PodcastIndexValidator`

```kotlin
interface PodcastIndexValidator {
    suspend fun validate(creds: PodcastIndexCreds): Result<Unit>
}
```

- Default impl performs **one cheap authenticated call** — `trending(max = 1)` — using a throwaway
  `Ktor3PodcastIndexClient` built from the candidate creds.
- `401`/`403` → `Result.failure(InvalidCredentials)`. Network/timeout → `Result.failure(Network)`.
  `2xx` → `Result.success`.
- Injected as a seam so the ViewModel test can fake it.

### 5. UI — `PodcastIndexSetupScreen` + `PodcastIndexSetupViewModel`

Mirrors `AiSetupScreen`/`AiSetupViewModel`, reached from a **Settings row** (the row + nav route are
shown only when `PodcastIndexCredentials.key.isBlank()`).

```kotlin
data class PodcastIndexSetupUiState(
    val connected: Boolean = false,
    val keyValue: String = "",
    val secretValue: String = "",
    val verifying: Boolean = false,
    val errorMessage: String? = null,
    val showDisconnectConfirm: Boolean = false,
)

class PodcastIndexSetupViewModel(
    private val config: PodcastIndexConfigRepository,
    private val validator: PodcastIndexValidator,
) : ViewModel()
```

- Two `OutlinedTextField`s: **Key** and **Secret** (secret field visually masked), disabled while
  `verifying`. Inline `errorMessage` below.
- **Connect** button: label toggles "Connect" ↔ "Verifying…"; guards double-submission; on success
  `config.setCredentials(...)` then clears the input fields; on failure maps the error to copy.
- **Connected footer** with **Disconnect** + a confirm dialog → `config.disconnect()`.
- Helper text with a link to <https://podcastindex.org/api> ("Get a free key").

### 6. DI wiring

- `CommonModule`: `PodcastIndexConfigRepository`, `EffectivePodcastIndexCredentials`,
  `PodcastIndexValidator` (default impl), the `ReconfigurablePodcastIndexClient` bound as
  `PodcastIndexClient`, and `single { PodcastIndexApi(get()) }`. `viewModel { PodcastIndexSetupViewModel(...) }`.
- `AndroidModule`: `single<PodcastIndexCredentialStore> { AndroidPodcastIndexCredentialStore(androidContext()) }`.
- `IosPlatformModule`: `single<PodcastIndexCredentialStore> { IosPodcastIndexCredentialStoreStub() }`.

## Data flow

1. FOSS user opens Settings → sees "Podcast Index API" row (build-time key is blank) → opens setup.
2. Enters key + secret → taps **Connect**.
3. `PodcastIndexSetupViewModel` calls `validator.validate(creds)` (one `trending?max=1` call).
4. On success → `config.setCredentials(creds)` → encrypted `commit()` → `isConfigured` flips true.
5. Next search resolves effective creds via `EffectivePodcastIndexCredentials` → BYOK creds win →
   `ReconfigurablePodcastIndexClient` rebuilds its delegate with the new creds → search succeeds.
6. **Disconnect** → `config.disconnect()` → store cleared → next resolve falls back to build-time
   (blank for FOSS) → search returns to the "no key" error state.

## Error handling

| Condition | Surfaced as |
|-----------|-------------|
| Validation 401/403 | "That key or secret was rejected. Double-check both values." |
| Validation network/timeout | "Couldn't reach Podcast Index. Check your connection and try again." |
| Store write failure | Flag stays false; "Couldn't save your credentials. Try again." (no silent success) |
| Search with no creds (FOSS, not yet configured) | Existing `NetworkErrorHandler` path; copy may be improved to hint at Settings (optional, see Open questions). |

## Testing

Per project conventions (commonTest Compose/logic tests + Paparazzi snapshots; no instrumentation).

- **`PodcastIndexConfigRepositoryTest`** — set persists + flips flag; clear resets; startup hydration
  reflects stored creds; flag does not flip when the store throws (fake store).
- **`EffectivePodcastIndexCredentialsTest`** — returns BYOK creds when configured; falls back to
  build-time creds when not; treats a half-filled/blank store as "not configured".
- **`ReconfigurablePodcastIndexClientTest`** — builds once; reuses the delegate when creds are
  unchanged; rebuilds exactly once when creds change; concurrent calls don't double-build (Mutex).
- **`PodcastIndexSetupViewModelTest`** — validate-before-save (no persist on validation failure);
  error mapping for 401 vs network; disconnect clears; double-tap Connect guarded.
- **Paparazzi** — `PodcastIndexSetupScreen` states: empty, verifying, error, connected.

All tests audited (test-quality-auditor) before running; `testFossDebugUnitTest` + `testPlayDebugUnitTest`
green; iOS compile green; ktlint + detekt clean; on-device exercise on a FOSS debug build (enter a real
key from podcastindex.org, confirm search works, disconnect, confirm it stops).

## Files

**New (commonMain):** `data/api/PodcastIndexCredentialStore.kt`, `data/api/PodcastIndexConfigRepository.kt`,
`data/api/EffectivePodcastIndexCredentials.kt`, `data/api/ReconfigurablePodcastIndexClient.kt`,
`data/api/PodcastIndexValidator.kt`, `ui/screens/settings/podcastindex/PodcastIndexSetupScreen.kt`,
`ui/screens/settings/podcastindex/PodcastIndexSetupViewModel.kt`.

**New (androidMain):** `data/api/PodcastIndexCredentialStore.android.kt`.
**New (iosMain):** `data/api/PodcastIndexCredentialStore.ios.kt`.

**Modified:** `data/api/PodcastIndexApi.kt` (drop `create()`, take injected client),
`di/CommonModule.kt`, `di/AndroidModule.kt`, `di/IosPlatformModule.kt`, the Settings screen + nav
(add the conditional row + route). Possibly `backup_rules_legacy.xml` if it lacks the `kofipod_secure`
exclusion.

**Tests:** the five test classes above + Paparazzi baselines.

## Open questions (decide during planning, non-blocking)

1. Improve the FOSS "no creds yet" search error to explicitly point at Settings → Podcast Index?
   (Nice-to-have; default: leave existing generic error, since the Settings row already exists.)
2. Should `trending(max=1)` be the validation call, or a `searchByTerm("test", limit=1)`? `trending`
   is cheaper and needs no query; default to `trending`.
