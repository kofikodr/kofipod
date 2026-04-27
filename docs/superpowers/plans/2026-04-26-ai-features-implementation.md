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

# Slice 2 — Episode summary panel, happy path

**User-facing outcome:** On the episode detail screen, a new AI panel appears below the description (only when a key is configured). Tap "Generate AI summary" → progress → markdown summary appears and persists. No entity extraction yet.

**Verifiable on emulator:** Download an episode → open detail → tap Generate → see summary in <90s (depending on episode length and network) → kill app → reopen detail → summary is still there.

### Task 2.1: SQLDelight table + schema bump

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/EpisodeAiSummary.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/12.sqm`
- Modify: `CLAUDE.md` — bump the "current schema version" line to 12 (it's at 11 after rebasing onto master's episode-detail Slice 4; Slice 2 nudges to 12).

- [ ] **Step 1: `EpisodeAiSummary.sq`** — schema per the spec:

  ```sql
  CREATE TABLE EpisodeAiSummary (
      episodeId       TEXT NOT NULL PRIMARY KEY,
      generatedAtMs   INTEGER NOT NULL,
      modelId         TEXT NOT NULL,
      audioBytes      INTEGER NOT NULL,
      summary         TEXT NOT NULL,
      peopleJson      TEXT NOT NULL DEFAULT '[]',
      thingsJson      TEXT NOT NULL DEFAULT '[]',
      linksJson       TEXT NOT NULL DEFAULT '[]'
  );

  selectByEpisode:
  SELECT * FROM EpisodeAiSummary WHERE episodeId = ?;

  upsert:
  INSERT OR REPLACE INTO EpisodeAiSummary
      (episodeId, generatedAtMs, modelId, audioBytes, summary, peopleJson, thingsJson, linksJson)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?);

  deleteByEpisode:
  DELETE FROM EpisodeAiSummary WHERE episodeId = ?;

  deleteAll:
  DELETE FROM EpisodeAiSummary;
  ```

  The entity-JSON columns ship empty (`'[]'`) in Slice 2 and get populated in Slice 3.

- [ ] **Step 2: `12.sqm`** — exactly the `CREATE TABLE` block above (no other changes).

- [ ] **Step 3: CLAUDE.md** — bump "current schema version is **11**" to "current schema version is **12**". One-line edit.

- [ ] **Step 4: green-check.** SQLDelight code-gen happens during `compileDebugKotlinAndroid`. Commit `feat(ai): add EpisodeAiSummary table (schema v12)`.

### Task 2.2: GeminiClient — Files API upload + generateContent for audio

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiPrompts.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/GeminiClient.kt`

- [ ] **Step 1: `AiPrompts.kt`** — single function `episodeSummaryPrompt(localeTag: String): String` returning a stable prompt. The Slice 2 prompt asks for **summary only** (markdown body, ~200 words, no headers); entity extraction comes in Slice 3. Prompt explicitly instructs the model to output the summary in the language matching `localeTag` and to omit any preamble.

- [ ] **Step 2: `GeminiClient` — add `uploadAudio(...)` method.** Resumable upload to the Files API:
  1. `POST https://generativelanguage.googleapis.com/upload/v1beta/files?key=…&uploadType=resumable` with `X-Goog-Upload-Protocol: resumable`, `X-Goog-Upload-Command: start`, `X-Goog-Upload-Header-Content-Length: <fileSize>`, `X-Goog-Upload-Header-Content-Type: audio/mpeg`, body `{"file": {"display_name": "<episodeId>.mp3"}}`. Capture `X-Goog-Upload-URL` from the response headers.
  2. `PUT <uploadUrl>` with headers `X-Goog-Upload-Command: upload, finalize`, `X-Goog-Upload-Offset: 0`, `Content-Length: <fileSize>`. Body is a Ktor `OutgoingContent.WriteChannelContent` that streams from a KMP `okio.FileSystem.SOURCE` (or use the existing `kotlinx-io` source already in the project — match what `DownloadRepository` uses).
  3. Parse the JSON response into `UploadedFile(name: String, uri: String, mimeType: String, sizeBytes: Long, state: String)`.
  4. Poll `GET /v1beta/files/{name}?key=…` until `state == "ACTIVE"` (audio files take a few seconds to process). Cap at 30s; on timeout map to `AiError.Unknown`.

- [ ] **Step 3: `GeminiClient` — add `generateSummary(...)` method.** Posts to `/v1beta/models/{model}:generateContent?key=…` with body:

  ```json
  {
    "contents": [{
      "parts": [
        { "fileData": { "mimeType": "audio/mpeg", "fileUri": "<uri>" }},
        { "text": "<prompt>" }
      ]
    }],
    "generationConfig": { "temperature": 0.4, "maxOutputTokens": 512 }
  }
  ```

  Parse `candidates[0].content.parts[0].text` out as the summary.

- [ ] **Step 4: `GeminiClient` — add `deleteFile(name)` for cleanup** (best-effort; ignore failures).

- [ ] **Step 5: error mapping.** Status 429 → `RateLimited`. 400 with `INVALID_ARGUMENT` containing "exceeds the maximum" or token-budget messaging → `AudioTooLong`. 401/403 → `KeyInvalid`. IOException → `Network`. Else `Unknown(statusCode)`.

- [ ] **Step 6: unit test.** `AiPromptsTest` covering the locale tag substitution. No live-network test in this slice.

- [ ] **Step 7: green-check.** Commit `feat(ai): add Files API upload + generateContent for audio`.

### Task 2.3: AiSummaryRepository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryDto.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — `single { AiSummaryRepository(get(), get(), get(), get(named("appScope"))) }`

- [ ] **Step 1: `AiSummaryDto.kt`** — domain types only (no JSON shape):

  ```kotlin
  data class AiSummary(
      val episodeId: String,
      val generatedAtMs: Long,
      val modelId: String,
      val audioBytes: Long,
      val summary: String,
      val people: List<String> = emptyList(),
      val things: List<String> = emptyList(),
      val links: List<MentionedLink> = emptyList(),
  )
  data class MentionedLink(val label: String, val url: String)
  ```

- [ ] **Step 2: `AiSummaryRepository.kt`** — exposes:
  - `observeFor(episodeId: String): Flow<AiSummaryUiState>` where `AiSummaryUiState` is one of `Hidden, Idle, Generating, Ready(AiSummary), Error(AiError)`.
  - `suspend fun generate(episodeId: String)` — orchestrates: read key (KeyVault) → if absent emit `Hidden`; resolve local file path (delegate to `EpisodesRepository` / `DownloadRepository`); check duration → if >8h emit `Error(AudioTooLong)`; upload → wait for `ACTIVE` → call `generateSummary` → persist via `EpisodeAiSummaryQueries.upsert` → delete remote file. The whole pipeline runs on the named `"appScope"` scope; `generate` returns immediately and the UI tracks the persisted state via `observeFor`.
  - `suspend fun clearAll()` — wipes the entire `EpisodeAiSummary` table. Wired up by Slice 4 Disconnect.
  - Cache invalidation: `observeFor` emits `Idle` when `cached.audioBytes != currentFileSize` (i.e. file was redownloaded). The UI shows a "Regenerate" affordance in that branch.
  - `generate` is idempotent per episode while a job is in flight — second invocation returns the existing job.

- [ ] **Step 3: green-check.** Commit `feat(ai): add AiSummaryRepository orchestrating upload→generate→persist`.

### Task 2.4: AI panel UI on episode detail

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/AiSummaryPanel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/AiSummaryViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/PodcastDetailScreen.kt` (or wherever the episode detail / row pane lives) — embed `AiSummaryPanel(episodeId)` under the description.
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — `viewModel { AiSummaryViewModel(get(), get<EpisodesRepository>()) }`

- [ ] **Step 1: `AiSummaryViewModel`** — thin: collects `repo.observeFor(episodeId)` into a `StateFlow<AiSummaryUiState>`; `onGenerate()` calls `repo.generate(episodeId)`; `onCancel()` cancels the in-flight job.

- [ ] **Step 2: `AiSummaryPanel`** — renders the five states from spec § V1 feature surface → "Episode AI panel". Slice 2 implementation:
  - `Hidden`: returns nothing (`Box {}` short-circuit).
  - `Idle`: outline button "Generate AI summary" + helper line.
  - `Generating`: progress indicator + label ("Uploading audio…", "Summarising…" once upload finishes — repository emits sub-state). Cancel button.
  - `Ready`: render `state.summary` as plain text (Slice 2 — markdown rendering can wait); footer with model name + relative date + Regenerate.
  - `Error(AiError.AudioTooLong)`: disabled button + helper line per spec.
  - All other errors: simple message + Retry button (full per-error mapping lands in Slice 4; Slice 2 can use a single fallback string).

- [ ] **Step 3: detail screen integration.** Insert `AiSummaryPanel(episodeId)` under the description, above the existing chapter / episode-list block. Honour the perf invariants in `CLAUDE.md`: do NOT inject AI state into `EpisodeRowData`; the panel is a sibling composable, not part of the row list.

- [ ] **Step 4: emulator verification.**
  - Connect a Gemini key (Slice 1).
  - Download a 5-minute test episode (use any short podcast — e.g. a daily news flash).
  - Open detail → AI panel renders Idle.
  - Tap Generate → Generating progresses → Ready within ~30s with a sane summary.
  - Force-stop the app → reopen detail → summary still rendered (DB persistence proven).

- [ ] **Step 5: green-check.** Commit `feat(ai): add AI summary panel on episode detail (happy path)`.

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

- [ ] **Step 1: snapshot test class.** Match the existing `TokensSnapshots` pattern. One snapshot per state × theme = 10 baselines: `Idle / Generating / Ready / Error.RateLimited / Error.AudioTooLong` × `light / dark`. Use fake state — never go through a real `AiSummaryRepository`. The "Ready" state uses a baked fixture summary.

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
