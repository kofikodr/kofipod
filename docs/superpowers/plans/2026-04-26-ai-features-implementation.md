# AI Features (BYOK Gemini) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL — use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each phase ends with a verifiable green-check (compile + ktlintFormat + detekt + tests + iOS sim-arm64 compile + emulator interaction where the slice has UI).

**Spec:** `docs/superpowers/specs/2026-04-26-ai-features-design.md` — read first.

**Goal:** Ship the v1 AI surface from the spec — a per-episode summary + entity panel powered by a user-supplied Gemini key, with a Settings → AI features setup flow. Five slices, each one independently shippable and verifiable on the Pixel_9a AVD.

---

## Conventions

- **Commits:** every task ends with one commit. Format `type(scope): subject` where `scope` is `ai` for everything in this plan (e.g. `feat(ai): add KeyVault expect/actual`).
- **SPDX header** on every new Kotlin file:

  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

- **Package root for this work:** `app.kofipod.ai`.
- **Green-check sequence per slice:**

  ```
  ./gradlew :composeApp:compileDebugKotlinAndroid
  ./gradlew :composeApp:ktlintFormat :composeApp:detekt
  ./gradlew :composeApp:testDebugUnitTest
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64
  # then per-slice emulator interaction (see slice's "Verify on emulator" step)
  ```

- **Detekt forbidden imports:** every new Android-only artefact added to `androidMain` gets added to `config/detekt/detekt.yml` `style>ForbiddenImport>imports` so it can't leak into `commonMain`.
- **Koin ViewModel factories:** every new ViewModel constructor parameter must land in `di/CommonModule.kt` in the same commit — per the lockstep rule in `CLAUDE.md`.
- **No prompts, responses, audio bytes, or any portion of the API key in any log.** Network failures log status code + short reason only.

---

# Slice 1 — Settings entry, KeyVault, key validation

**User-facing outcome:** A new "AI features (optional)" section in Settings opens an AI Setup screen. The user can paste a Gemini key, see it validated against a tiny test request, save it, and disconnect. No episode UI yet.

**Verifiable on emulator:** Open Settings → AI features → paste real key → see "Connected · Model: Flash". Disconnect wipes the key.

### Task 1.1: KeyVault expect/actual + Android backing

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/KeyVault.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/ai/KeyVault.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/ai/KeyVault.ios.kt`
- Modify: `composeApp/build.gradle.kts` — add `androidx.security:security-crypto` to `androidMain`
- Modify: `gradle/libs.versions.toml` — add the version + library alias
- Modify: `config/detekt/detekt.yml` — add `androidx.security.crypto.*` to `ForbiddenImport`

- [ ] **Step 1: `gradle/libs.versions.toml`** — add:

  ```toml
  androidxSecurityCrypto = "1.1.0-alpha06"
  ```

  and under `[libraries]`:

  ```toml
  androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "androidxSecurityCrypto" }
  ```

- [ ] **Step 2: `composeApp/build.gradle.kts`** — wire into the `androidMain` sourceSet (NOT `commonMain`):

  ```kotlin
  val androidMain by getting {
      dependencies {
          // …existing deps…
          implementation(libs.androidx.security.crypto)
      }
  }
  ```

- [ ] **Step 3: `commonMain` expect**

  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  package app.kofipod.ai

  expect class KeyVault {
      suspend fun get(): String?
      suspend fun set(value: String)
      suspend fun clear()
  }
  ```

- [ ] **Step 4: `androidMain` actual** — `EncryptedSharedPreferences` file `kofipod_secure`, key name `gemini_api_key`. Use `MasterKey.Builder(...).setKeyScheme(AES256_GCM)`. All ops via `withContext(Dispatchers.IO)`.

- [ ] **Step 5: `iosMain` actual stub** — returns `null`/no-op for now. Add a `// TODO(ios): Keychain via platform.Security` comment.

- [ ] **Step 6: detekt config** — add `androidx.security.crypto.*` to the forbidden-import list, alongside the existing entries.

- [ ] **Step 7: green-check** (no UI yet, no emulator step). Commit `feat(ai): add KeyVault expect/actual with EncryptedSharedPreferences backing`.

### Task 1.2: GeminiClient (validation request only)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiModels.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiError.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiClient.kt`

- [ ] **Step 1: `GeminiModels.kt`** — enum with two members; carry the API model id + display name + a per-second token rate (32) for future use:

  ```kotlin
  enum class GeminiModel(val apiId: String, val displayName: String) {
      Flash("gemini-2.5-flash", "Flash"),
      FlashLite("gemini-2.5-flash-lite", "Flash-Lite"),
  }
  ```

- [ ] **Step 2: `AiError.kt`** — sealed class:

  ```kotlin
  sealed class AiError {
      object NoKey : AiError()
      object KeyInvalid : AiError()
      object RateLimited : AiError()
      object AudioTooLong : AiError()
      object Network : AiError()
      data class Unknown(val statusCode: Int? = null) : AiError()
  }
  ```

- [ ] **Step 3: `GeminiClient.kt`** — built on the existing Ktor `HttpClient` from `data/net/HttpClientFactory`. For Slice 1 only one method: `suspend fun validate(apiKey: String, model: GeminiModel): Result<Unit>`. Body: a `generateContent` call with a 4-token prompt ("Say OK") and `maxOutputTokens=4`. Map HTTP 400/401/403 → `KeyInvalid`, 429 → `RateLimited`, IOException → `Network`, else `Unknown`. The key goes in the `?key=` query param at request scope, never persisted into the client.

- [ ] **Step 4: green-check.** Commit `feat(ai): add GeminiClient with key-validation request`.

### Task 1.3: AI Setup screen + Settings entry row

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/ai/AiSetupViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/ai/AiSetupScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt` — add the new "AI features (optional)" row
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt` — expose `aiConnected: StateFlow<Boolean>` + `aiModelName: StateFlow<String>`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Route.kt` — add `Route.AiSetup`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/NavGraph.kt` (or wherever `NavHost` lives) — register `Route.AiSetup`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — `single { KeyVault(get()) }`, `single { GeminiClient(get()) }`, `viewModel { AiSetupViewModel(get(), get(), get()) }` (keyVault + client + persisted model-id store)

- [ ] **Step 1: `AiSetupViewModel`** — state machine:

  ```kotlin
  data class AiSetupState(
      val connected: Boolean,
      val model: GeminiModel,
      val pasteValue: String = "",
      val verifying: Boolean = false,
      val errorMessage: String? = null,
  )
  ```

  Methods: `onPaste(String)`, `setModel(GeminiModel)`, `connect()` (calls `client.validate`, on success persists key + model, on failure surfaces a specific message), `disconnect()` (clears key and emits `cachedSummariesCleared` event for Slice 4 to wire up).

- [ ] **Step 2: `AiSetupScreen`** — stateful UI per the spec § V1 feature surface → "Settings → AI features":
  1. Disclosure card (3 short paragraphs, last paragraph bold).
  2. "Get a free Gemini API key" outline button → opens `https://aistudio.google.com/app/apikey` via the existing `Sharer`/`UriHandler` pattern (use `androidx.compose.ui.platform.LocalUriHandler` from `commonMain`).
  3. Paste field (`autocorrect=false`, `keyboardOptions = KeyboardOptions(autoCorrect = false, capitalization = None)`, monospace font, password visual transformation OFF — the user must see what they paste).
  4. Model picker — segmented control or radio rows; Flash default.
  5. Connect button — disabled while `verifying`. Disconnect button — only shown when `connected`. Disconnect requires a confirm dialog.
  6. Footer: connected state shows "Connected · {modelName}".

  Visual style is the design agent's call (spec: "open" list). For this plan, follow the existing settings rows / `KofipodTheme` tokens.

- [ ] **Step 3: Settings entry row** — wedge a new `Section("AI features (optional)")` block after the Theme section. Connected state: subtitle = "Model: {model} · Tap to manage". Disconnected: "Optional. Enables on-device episode summaries." Tap navigates to `Route.AiSetup`.

- [ ] **Step 4: nav + Koin wiring.** Add `Route.AiSetup` and the `composable<Route.AiSetup> { AiSetupScreen(onBack = { nav.popBackStack() }) }` entry. Wire all three Koin singletons + the viewModel factory (lockstep rule).

- [ ] **Step 5: model-id persistence.** Add a tiny `AiPrefs` interface in `data/repo/SettingsRepository.kt` (or its own repository) with `model(): Flow<GeminiModel>` + `setModel(GeminiModel)`. Backed by the existing DataStore / SharedPreferences setup that Settings already uses. Default = `GeminiModel.Flash`.

- [ ] **Step 6: green-check + emulator interaction.**
  - Build & install: `./gradlew :composeApp:installDebug`.
  - Open Settings → tap AI features → AI Setup screen renders.
  - Paste a known-bad key (e.g. `xxx`), tap Connect, see "Your Gemini key was rejected".
  - Paste a real free-tier key, tap Connect, see "Connected · Flash" within ~3s.
  - Back to Settings, row reads "Model: Flash · Tap to manage".
  - Tap → Disconnect → confirm → row returns to disconnected state.

  Commit `feat(ai): add AI Setup screen with key validation and Settings entry`.

### Task 1.4: Backup-rules exclusion for the secure prefs file

**Files:**
- Modify: `composeApp/src/androidMain/res/xml/backup_rules.xml`
- Modify: `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml`

- [ ] **Step 1: `backup_rules.xml`** — add the exclude inside both `<cloud-backup>` and `<device-transfer>`:

  ```xml
  <exclude domain="sharedpref" path="kofipod_secure.xml" />
  ```

  Update the file's header comment to mention that `kofipod_secure` holds the user's BYOK Gemini key and must never sync.

- [ ] **Step 2: `backup_rules_legacy.xml`** — add the same `<exclude>` inside `<full-backup-content>`. Keep the comment in sync.

- [ ] **Step 3: green-check.** Commit `feat(ai): exclude kofipod_secure from Auto Backup and device transfer`.

---

# Slice 2 — Episode summary panel, transcript path

**User-facing outcome:** On the episode detail screen, a new AI panel appears below the description (only when a key is configured AND the episode's feed publishes a Podcasting 2.0 transcript). Tap "Generate AI summary" → fetch the transcript → Gemini returns a clean prose summary → it persists across app restarts. No entity extraction, no audio upload — that's Slice 2.5.

**Verifiable on emulator:** Open an episode whose feed ships a transcript (e.g. a Buzzsprout-hosted show or any Podcasting-2.0-friendly publisher) → tap Generate → see summary in ≤15s → kill app → reopen detail → summary is still there. Episodes without a transcript show a hint ("Audio summary coming in a future update.") with Generate disabled.

### Task 2.1: SQLDelight table + schema bump

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/EpisodeAiSummary.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/12.sqm`
- Modify: `CLAUDE.md` — bump the "current schema version" line to 12 (it's at 11 after rebasing onto master's episode-detail Slice 4; Slice 2 nudges to 12).

- [ ] **Step 1: `EpisodeAiSummary.sq`** — schema per the spec:

  ```sql
  CREATE TABLE EpisodeAiSummary (
      episodeId         TEXT NOT NULL PRIMARY KEY,
      generatedAtMs     INTEGER NOT NULL,
      modelId           TEXT NOT NULL,
      sourceKind        TEXT NOT NULL,                 -- 'transcript' | 'audio'
      sourceFingerprint TEXT NOT NULL,                 -- transcript: URL; audio: byte count
      summary           TEXT NOT NULL,
      peopleJson        TEXT NOT NULL DEFAULT '[]',
      thingsJson        TEXT NOT NULL DEFAULT '[]',
      linksJson         TEXT NOT NULL DEFAULT '[]'
  );

  selectByEpisode:
  SELECT * FROM EpisodeAiSummary WHERE episodeId = ?;

  upsert:
  INSERT OR REPLACE INTO EpisodeAiSummary
      (episodeId, generatedAtMs, modelId, sourceKind, sourceFingerprint, summary, peopleJson, thingsJson, linksJson)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

  deleteByEpisode:
  DELETE FROM EpisodeAiSummary WHERE episodeId = ?;

  deleteAll:
  DELETE FROM EpisodeAiSummary;
  ```

  The entity-JSON columns ship empty (`'[]'`) in Slice 2 and get populated in Slice 3. `(sourceKind, sourceFingerprint)` is the cache-invalidation key — see the spec for the rules.

- [ ] **Step 2: `12.sqm`** — exactly the `CREATE TABLE` block above (no other changes).

- [ ] **Step 3: CLAUDE.md** — bump "current schema version is **11**" to "current schema version is **12**". One-line edit.

- [ ] **Step 4: green-check.** SQLDelight code-gen happens during `compileDebugKotlinAndroid`. Commit `feat(ai): add EpisodeAiSummary table (schema v12)`.

### Task 2.2: AiPrompts + GeminiClient.generateFromText

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiPrompts.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiClient.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiError.kt` — add `data object TranscriptUnavailable : AiError()`

- [ ] **Step 1: `AiPrompts.kt`** — `episodeSummaryPrompt(localeTag: String): String`. The prompt MUST:
  - Tell the model the input may be VTT, SRT, JSON, or plain text — and to ignore cue numbers, timestamps, speaker prefixes, and HTML tags. Format detection is its job.
  - Request a markdown body, ~200 words, no headers, no preamble, no code fences.
  - Instruct output language to match `localeTag` (BCP-47, e.g. `en-US`).
  - Be a single, stable string — change-management is via prompt-version, not interpolation.

- [ ] **Step 2: `GeminiClient.generateFromText(apiKey, model, prompt, content): Result<String>`.** Two `text` parts in one `Content`: first the prompt, second the `content` (the transcript body verbatim — no preprocessing). `generationConfig` = `{ temperature: 0.4, maxOutputTokens: 512 }`. Parse `candidates[0].content.parts[0].text` and return as the summary string. Same `AiHttpClient` already in tree.

- [ ] **Step 3: error mapping.** Status 400/401/403 → `KeyInvalid`. 429 → `RateLimited`. IOException → `Network`. Else → `Unknown(statusCode)`. (`AudioTooLong` is reserved for the Slice 2.5 audio path; transcript inputs that overflow the 1M context map to `Unknown` for now — extreme rarity.)

- [ ] **Step 4: unit tests.**
  - `AiPromptsTest` — locale-tag substitution + presence of the format-agnostic instruction (snapshot-style assertion on the rendered prompt).
  - `GeminiClientTextTest` — using a fake `KeyValidator`-style seam (the test pattern from Slice 1's `GeminiClientTest`), assert the request body shape and the `200 → Result.success(text)` / error mappings. No live network.

- [ ] **Step 5: green-check.** Commit `feat(ai): add generateFromText + transcript-aware prompt`.

### Task 2.3: AiSummaryRepository (transcript path only)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryDto.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — `single { AiSummaryRepository(...) }` with deps: `KofipodDatabase`, `AiConfigRepository`, `GeminiClient`, app-wide `HttpClient` (transcript fetch), `EpisodesRepository`, named `"appScope"`.

- [ ] **Step 1: `AiSummaryDto.kt`** — domain types:

  ```kotlin
  enum class AiSourceKind(val wire: String) {
      Transcript("transcript"),
      Audio("audio"),
  }

  data class AiSummary(
      val episodeId: String,
      val generatedAtMs: Long,
      val modelId: String,
      val sourceKind: AiSourceKind,
      val sourceFingerprint: String,
      val summary: String,
      val people: List<String> = emptyList(),
      val things: List<String> = emptyList(),
      val links: List<MentionedLink> = emptyList(),
  )

  data class MentionedLink(val label: String, val url: String)

  sealed interface AiSummaryUiState {
      data object Hidden : AiSummaryUiState                // no key configured
      data class Idle(val available: AiSourceKind?) : AiSummaryUiState   // null = neither input available
      data class Generating(val sourceKind: AiSourceKind) : AiSummaryUiState
      data class Ready(val summary: AiSummary, val stale: Boolean) : AiSummaryUiState
      data class Error(val error: AiError) : AiSummaryUiState
  }
  ```

- [ ] **Step 2: `AiSummaryRepository.kt`** — Slice 2 only handles the transcript path; the audio branch is a TODO that emits `Idle(available = null)` when the episode has no transcript. Public surface:
  - `observeFor(episodeId: String): Flow<AiSummaryUiState>` — combines: `aiConfig.isKeyConfigured`, `episodesRepo.episodeFlow(episodeId)`, the cached-summary row Flow (`db.episodeAiSummaryQueries.selectByEpisode`), and the in-flight job state (a `MutableStateFlow<Map<String, AiSourceKind>>` keyed by episodeId). Resolution rules:
    - No key → `Hidden`.
    - In-flight → `Generating(sourceKind)`.
    - Cached row exists → `Ready(summary, stale = sourceFingerprintMismatch)`.
    - No cached row, transcriptUrl present → `Idle(available = Transcript)`.
    - No cached row, no transcript → `Idle(available = null)` for now (Slice 2.5 changes this branch to `Audio` when downloaded).
  - `fun generate(episodeId: String)` — non-suspending; launches on `"appScope"`. Pipeline:
    1. Resolve key via `aiConfig.currentKey()`; if null emit `Error(NoKey)` and return.
    2. Resolve `episode = episodesRepo.byId(episodeId)`; if null bail silently.
    3. Pick source — Slice 2: only transcript. If `transcriptUrl` is blank emit `Error(TranscriptUnavailable)` (Slice 2.5 swaps in audio).
    4. Mark in-flight (`Generating(Transcript)`).
    5. `appHttp.get(transcriptUrl).bodyAsText()`. Non-2xx → `Error(TranscriptUnavailable)`.
    6. `geminiClient.generateFromText(key, model, prompt, body)`. Map failure to the right `AiError`.
    7. Upsert with `sourceKind = 'transcript'`, `sourceFingerprint = transcriptUrl`, `peopleJson = thingsJson = linksJson = '[]'`.
    8. Clear in-flight.
  - `suspend fun clearAll()` — wipes the table. Wired up in Slice 4.
  - Idempotency: a second `generate(id)` while one is in-flight is a no-op (look up the in-flight map).

- [ ] **Step 3: unit tests.** `AiSummaryRepositoryTest` covering:
  - No key → `Hidden`.
  - Cached row, fingerprint matches → `Ready(stale = false)`.
  - Cached row, fingerprint mismatches → `Ready(stale = true)`.
  - No cache, transcript URL present → `Idle(Transcript)`.
  - `generate` happy path — fake `GeminiClient` + fake `HttpClient` (use Ktor `MockEngine` on the app-side client, and the existing fake-`KeyValidator` pattern for Gemini; or extract a tiny `TextGenerator fun interface` for clean unit test seams).
  - `generate` with blank `transcriptUrl` → `Error(TranscriptUnavailable)`.

- [ ] **Step 4: green-check.** Commit `feat(ai): add AiSummaryRepository (transcript path)`.

### Task 2.4: AI panel UI on EpisodeDetailScreen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/AiSummaryPanel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/AiSummaryViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` — insert `AiSummaryPanel(episodeId)` between the description block (around line 213-224) and the chapters section (line 226-229).
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — `viewModel { (episodeId: String) -> AiSummaryViewModel(episodeId, get()) }`.

- [ ] **Step 1: `AiSummaryViewModel(episodeId, repo)`** — thin. `state: StateFlow<AiSummaryUiState>` from `repo.observeFor(episodeId)` via `stateIn(viewModelScope, WhileSubscribed(5_000), Hidden)`. `onGenerate()` → `repo.generate(episodeId)`. (No `onCancel` in Slice 2 — transcript fetches are seconds, not minutes.)

- [ ] **Step 2: `AiSummaryPanel(episodeId)`** — Composable obtains its VM via `koinViewModel { parametersOf(episodeId) }`. Renders:
  - `Hidden`: returns nothing.
  - `Idle(available = Transcript)`: outline button "Generate AI summary" + helper line "Uses your Gemini key. Reads this episode's published transcript."
  - `Idle(available = null)`: disabled button + helper line "Audio summary coming in a future update." (Slice 2.5 deletes this branch.)
  - `Generating(_)`: small linear progress indicator + label "Summarising…".
  - `Ready(summary, stale)`: summary text (plain text, monospace fallback so HTML/markdown isn't mistakenly rendered raw); footer row with `Model: {modelName}` + relative date + "Regenerate" outline button. When `stale = true`, prepend a one-line hint "Source updated — regenerate for the latest version." above the summary.
  - `Error(_)`: simple message + Retry. Full per-error mapping is Slice 4.

  Visual style: follow existing `KofipodTheme` tokens; match the chapters section's section-header treatment for consistency. Test tags `aiPanelGenerateButton`, `aiPanelRegenerateButton`, `aiPanelRetryButton` for emulator scripting.

- [ ] **Step 3: detail screen integration.** In `EpisodeDetailScreen.EpisodeBody`, after the description block:

  ```kotlin
  Spacer(Modifier.height(20.dp))
  AiSummaryPanel(episodeId = episode.id)
  ```

  Do NOT inject AI state into `EpisodeRowData` or `EpisodeDetailUiState` — the panel is a sibling composable with its own VM, preserving the perf invariants in `CLAUDE.md`.

- [ ] **Step 4: emulator verification.**
  - Connect a Gemini key (Slice 1).
  - Subscribe to a podcast that ships transcripts (e.g. a Buzzsprout-hosted show, NPR, or any Podcasting 2.0 publisher) — verify by inspecting the episode row in `Episode.sq` for a non-blank `transcriptUrl` via `adb shell run-as app.kofipod.debug sqlite3 …`.
  - Open the episode detail → AI panel renders `Idle(Transcript)`.
  - Tap Generate → `Generating` shows briefly → `Ready` within ~10s with a sane summary.
  - Force-stop the app → reopen detail → summary still rendered (DB persistence).
  - Open a podcast that does NOT publish transcripts → panel renders `Idle(available = null)` with the disabled-button hint.

- [ ] **Step 5: green-check.** Commit `feat(ai): add AI summary panel on episode detail (transcript path)`.

---

# Slice 2.5 — Audio fallback (Files API)

**User-facing outcome:** Episodes without a publisher-supplied transcript can now be summarised too — provided they're already downloaded. The same panel, same prompt, same output; only the input pipeline changes.

**Verifiable on emulator:** Subscribe to a podcast without transcripts → download an episode → open detail → panel renders `Idle(Audio)` → tap Generate → upload progress → summary in ~30–90s depending on length.

### Task 2.5.1: GeminiClient — Files API methods

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiClient.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiError.kt` — confirm `AudioTooLong` mapping reused.

- [ ] **Step 1: `uploadAudio(apiKey, model, fileSource, mimeType, sizeBytes, displayName): Result<UploadedFile>`.** Resumable upload:
  1. `POST /upload/v1beta/files?key=…&uploadType=resumable` with `X-Goog-Upload-Protocol: resumable`, `X-Goog-Upload-Command: start`, `X-Goog-Upload-Header-Content-Length: <sizeBytes>`, `X-Goog-Upload-Header-Content-Type: <mimeType>`. Body: `{"file": {"display_name": "<displayName>"}}`. Capture `X-Goog-Upload-URL` from response headers.
  2. `PUT <uploadUrl>` with `X-Goog-Upload-Command: upload, finalize`, `X-Goog-Upload-Offset: 0`, `Content-Length: <sizeBytes>`. Body: a Ktor `OutgoingContent.WriteChannelContent` over the file source — match what `DownloadRepository` already uses for streaming.
  3. Parse JSON into `UploadedFile(name, uri, mimeType, sizeBytes, state)`.

- [ ] **Step 2: `pollUntilActive(apiKey, name): Result<UploadedFile>`.** `GET /v1beta/files/{name}?key=…` every 1s until `state == "ACTIVE"`. Cap at 30s; on timeout map to `AiError.Unknown(null)`. (Bumping to 60s is a pre-approved follow-up if 30s proves tight in practice — see risk register.)

- [ ] **Step 3: `generateFromAudio(apiKey, model, fileUri, mimeType, prompt): Result<String>`.** Request body:

  ```json
  {
    "contents": [{
      "parts": [
        { "fileData": { "mimeType": "<mimeType>", "fileUri": "<fileUri>" }},
        { "text": "<prompt>" }
      ]
    }],
    "generationConfig": { "temperature": 0.4, "maxOutputTokens": 512 }
  }
  ```

  Parse `candidates[0].content.parts[0].text`.

- [ ] **Step 4: `deleteFile(apiKey, name)`** — `DELETE /v1beta/files/{name}?key=…`. Best-effort; ignore failures.

- [ ] **Step 5: error mapping.** Same as text path PLUS: 400 with `INVALID_ARGUMENT` containing "exceeds the maximum" or token-budget messaging → `AudioTooLong`.

- [ ] **Step 6: unit tests.** Mirror Slice 2's `GeminiClientTextTest` for the audio request shapes. Don't unit-test the resumable upload happy path with a real file — just assert request headers + body marshalling against a canned response.

- [ ] **Step 7: green-check.** Commit `feat(ai): add Files API upload + audio generateContent`.

### Task 2.5.2: AiSummaryRepository — audio branch

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — repo now also depends on `DownloadRepository` for `localPathFor(episodeId)`.

- [ ] **Step 1: source selector.** Update `pickSource(episode, downloaded): AiSourceKind?`:
  - `transcriptUrl` non-blank → `Transcript` (unchanged).
  - else if `downloaded.localPath` non-blank → `Audio`.
  - else → `null` (panel shows the "download to summarise" hint).

- [ ] **Step 2: audio pipeline.** When the selector picks `Audio`:
  1. `episode.durationSec > 8 * 3600` → `Error(AudioTooLong)` (soft-cap from spec).
  2. `geminiClient.uploadAudio(...)` with the local file size + path-backed source.
  3. `geminiClient.pollUntilActive(...)`.
  4. `geminiClient.generateFromAudio(...)`.
  5. Persist with `sourceKind = Audio`, `sourceFingerprint = <bytes>` (decimal string, matches the spec's "byte count" rule).
  6. Best-effort `geminiClient.deleteFile(...)` (ignore result).

- [ ] **Step 3: panel update.** `AiSummaryPanel`'s `Idle(available = Audio)` branch now renders the active Generate button (delete the disabled-hint dead branch from Slice 2; keep `Idle(available = null)` for the "neither" case where audio is not yet downloaded).

- [ ] **Step 4: emulator verification.**
  - Subscribe to a podcast known to NOT publish transcripts.
  - Download a 5-minute episode.
  - Open detail → `Idle(Audio)`.
  - Tap Generate → `Generating(Audio)` for ~30–90s → `Ready` with sane summary.
  - Force-stop + reopen → still there.
  - Try a >8h episode → see disabled button + `AudioTooLong` copy.

- [ ] **Step 5: green-check.** Commit `feat(ai): add audio fallback path via Files API`.

---

# Slice 3 — Entity extraction (people, things, links)

**User-facing outcome:** The Ready state on the AI panel grows three collapsible sections under the summary text: People, Books / things mentioned, Links.

**Verifiable on emulator:** Generate against an episode that mentions named guests, a book, and a URL → see all three populated.

### Task 3.1: Extend the prompt + JSON contract

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiPrompts.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryJson.kt` — `@Serializable` shapes
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiClient.kt` — `generateSummary` returns the structured shape, not raw text
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/ai/AiSummaryJsonTest.kt`
- Create: `composeApp/src/commonTest/resources/ai/sample_response.json` (recorded fixture)

- [ ] **Step 1: prompt rewrite.** The new prompt asks for a JSON object with four keys: `summary` (string, ~200 words), `people` (array of strings), `things` (array of strings), `links` (array of `{label, url}`). Include the explicit instruction "Respond with JSON only, no prose, no code fences." Use Gemini's `responseMimeType: "application/json"` + `responseSchema` so we get reliable structured output. The schema lives in `AiSummaryJson.kt`.

- [ ] **Step 2: `AiSummaryJson.kt`** — `@Serializable` data classes mirroring the response. Use kotlinx.serialization (already in the project).

- [ ] **Step 3: GeminiClient** — request body grows a `generationConfig.responseMimeType = "application/json"` and a `responseSchema`. Parse the response text as `AiSummaryJson`, map to `AiSummary`. On parse failure, log the parse error type only (not body) and emit `AiError.Unknown`.

- [ ] **Step 4: unit test.** Read `sample_response.json` from `commonTest` resources and assert it parses into `AiSummary` with expected counts. The fixture should be a captured real response from a 10-min test podcast (record once, commit the JSON, never call the API in tests).

- [ ] **Step 5: persist + render.** Repository upsert now fills `peopleJson`, `thingsJson`, `linksJson`. Panel renders the three sections under the summary; empty sections are hidden, not shown empty. Tapping a link uses `LocalUriHandler`.

- [ ] **Step 6: emulator verification.** Generate against an episode known to name guests + reference a book → see populated sections.

- [ ] **Step 7: green-check.** Commit `feat(ai): extract people, things, and links via structured JSON output`.

---

# Slice 4 — Error states + Disconnect cleanup

**User-facing outcome:** Every error path from the spec § "Error UX" maps to the right user-facing copy, with deep links into Settings where applicable. Disconnect wipes both the key and every cached summary.

### Task 4.1: Error message mapping

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiErrorMessage.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/AiSummaryPanel.kt`

- [ ] **Step 1: `AiErrorMessage.kt`** — a pure mapping function `aiErrorMessage(AiError): AiErrorPresentation` returning `(headline: String, actionLabel: String?, action: AiErrorAction?)` where `AiErrorAction` is `Retry | OpenAiSetup | None`. Strings exactly match the spec table.

- [ ] **Step 2: panel** — replace the Slice 2 fallback string with the full mapping. The `OpenAiSetup` action navigates to `Route.AiSetup`. `Retry` re-invokes `viewModel.onGenerate()`.

- [ ] **Step 3: unit tests** for the mapping (one assertion per `AiError` subtype). Pure-function tests, no fixtures.

- [ ] **Step 4: green-check.** Commit `feat(ai): wire full error-state UX with deep-links into AI Setup`.

### Task 4.2: Disconnect wipes summaries

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/ai/AiSetupViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt` (already has `clearAll`; just call it)

- [ ] **Step 1:** `AiSetupViewModel.disconnect()` now calls, in order: `keyVault.clear()` → `aiSummaryRepository.clearAll()`. The Disconnect confirm dialog gains a second sentence: "Cached AI summaries will also be removed from this device."

- [ ] **Step 2: emulator verification.** Generate a summary on episode A → Settings → Disconnect → confirm → revisit episode A → panel is `Hidden` (no key) → reconnect → panel returns to `Idle` (cached summary is gone, as expected).

- [ ] **Step 3: green-check.** Commit `feat(ai): clear cached summaries on Disconnect`.

---

# Slice 5 — Paparazzi baselines + final hardening

**User-facing outcome:** Visual regression tests cover the panel in every state, and the feature is locked in.

### Task 5.1: Paparazzi snapshots for the AI panel

**Files:**
- Create: `composeApp/src/test/kotlin/app/kofipod/screenshots/AiSummaryPanelSnapshots.kt`
- Snapshot images under `composeApp/src/test/snapshots/images/` (record via `recordPaparazziDebug`)

- [ ] **Step 1: snapshot test class.** Match the existing `TokensSnapshots` pattern. One snapshot per state × theme — 12 baselines: `Idle(Transcript) / Idle(available = null) / Generating / Ready / Error.RateLimited / Error.AudioTooLong` × `light / dark`. Use fake state — never go through a real `AiSummaryRepository`. The "Ready" state uses a baked fixture summary.

- [ ] **Step 2: record.** `./gradlew :composeApp:recordPaparazziDebug --tests "app.kofipod.screenshots.AiSummaryPanelSnapshots"` — commit the resulting PNGs.

- [ ] **Step 3: verify in CI mode.** `./gradlew :composeApp:verifyPaparazziDebug` should pass green from a clean checkout.

- [ ] **Step 4: green-check.** Commit `test(ai): add Paparazzi snapshots for AI panel states (10 baselines)`.

### Task 5.2: Final lint sweep + CLAUDE.md updates

**Files:**
- Modify: `CLAUDE.md` (if not already done in Slice 2 Task 2.1) — add a short section under "Architecture" describing the `ai/` package, the BYOK key-storage rule, and the schema-version reality check.
- Modify: `config/detekt/detekt.yml` (if any new Android-only artefact landed since Slice 1)

- [ ] **Step 1:** read CLAUDE.md, add an `### AI features` subsection summarising: "User-supplied Gemini key only. Stored in `kofipod_secure` `EncryptedSharedPreferences`, excluded from Auto Backup. Network code in `ai/GeminiClient.kt`. Cached summaries in the `EpisodeAiSummary` table — wiped on Disconnect. Key never enters logs or BuildKonfig."

- [ ] **Step 2: green-check** including `verifyPaparazziDebug`. Commit `docs(ai): document AI features module in CLAUDE.md`.

---

## Out-of-plan / "futures" register

These were considered and explicitly punted. Don't add them to this plan; they each warrant their own spec.

- **WorkManager-backed reliable summarisation** so jobs survive process death (currently `appScope` only).
- **"Ask this episode" Q&A** with chat surface and conversation memory.
- **Smart auto-chapters** with timestamps + a separate prompt-strategy spec.
- **Per-podcast auto-summary on download.**
- **Token / quota counter UI** (would require parsing `usageMetadata` from each response and aggregating).
- **iOS Keychain-backed `KeyVault`** so the iOS build can also configure AI.
- **Streaming response rendering** with Ktor SSE.
- **Markdown rendering of the summary body.** Slice 2 ships plain text; a rich-text renderer is a future polish.
- **Chunking pipeline** for episodes >8h.

## Risk register

- **Free-tier 250-RPD quota.** A power user could exhaust their daily quota in one session of bulk-summarising. The `RateLimited` error message is already user-friendly; if support load proves high, add a settings-level "AI usage today: X requests" readout.
- **Files API upload size on metered connections.** A 100MB upload over cellular is real money for the user. Slice 2 should at minimum gate on `NetworkMonitor.isUnmetered` — actually, no — leave that as a future. The disclosure card already warns about uploads; making it a hard block is a UX call we can take after first-use feedback.
- **Files API state never reaches `ACTIVE`.** The 30s poll cap in Task 2.2 prevents an infinite hang. If hit in practice, increase to 60s before adding fancier retry.
- **JSON schema drift.** Gemini's structured-output behaviour is stable but not contractual; the Slice 3 fixture will catch drift only when a developer regenerates it. Add a `// TODO: refresh fixture quarterly` comment in `AiSummaryJsonTest`.
