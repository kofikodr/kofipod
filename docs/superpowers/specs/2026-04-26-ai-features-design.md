# AI Features (BYOK Gemini) — Design Spec

**Date:** 2026-04-26
**Status:** Design locked, awaiting implementation plan
**Audience:** Design agent (visual/UX) → implementation plan

## Goal & framing

Add an **optional** AI layer to Kofipod that turns each downloaded episode into a useful written artefact (summary + extracted entities). The feature must:

- Cost the project zero per-user inference dollars. The user supplies their own Google Gemini API key (BYOK).
- Be fully removable. The app must work identically with no key configured — no nags, no upsell, no greyed-out screens.
- Stay private by default. Keys live only on-device. The user is told plainly what is sent to Google and that free-tier traffic may be used to train Google's models.

V1 ships **one** AI surface: per-episode summary + extracted entities (people, books, links). Q&A, smart chapters, and per-podcast taste recommendations are deferred to later slices.

## Why BYOK and why Gemini

- Google's Gemini free tier still issues keys with no billing account in 2026; quota is per-Google-Cloud-project so each user gets their own bucket.
- `gemini-2.5-flash` accepts native audio input (32 tokens/sec, 1M-token context, 9.5 h ceiling). A 3-hour podcast fits in one request — no chunking pipeline needed for v1.
- BYOK keeps Kofipod cost-free to operate and aligns with the existing Auto-Backup model (no in-app sign-in, no OAuth client to maintain).
- Trade-off: each user must paste a key once. This is friction. We accept it because the alternative is either burning money or a paid tier we don't want to operate.

The provider-specific bits (Gemini endpoints, model IDs, token rate, the 9.5h ceiling, free-tier disclosure) are scoped to one module so a future swap to a different BYOK provider is a single seam, not a rewrite. **No abstraction layer in v1** — just a clean module boundary.

## V1 feature surface

### Episode AI panel (the only AI-bearing UI in v1)

A new **AI panel** on the **Episode detail screen** (under the description, above any existing chapter list).

**States:**

| State | Trigger | Visual |
|---|---|---|
| `Hidden` | No Gemini key configured | The panel does not render at all. |
| `Idle` | Key configured, episode not yet summarised | Single button: "Generate AI summary". Helper line: "Uses your Gemini key. ~X minutes of audio." |
| `Generating` | Request in flight | Inline progress (upload → process → format). User can navigate away; job continues. Cancel button exits the request. |
| `Ready` | Summary cached | Summary text, then collapsible entity sections: **People**, **Books / things mentioned**, **Links**. Footer: model name + generated date + "Regenerate" button. |
| `Error` | Network / quota / key invalid | Plain-English message + Retry. Quota errors say "Your Gemini key is rate-limited — try again later" (do not say "Kofipod failed"). Invalid-key errors deep-link to Settings → AI. |

**Entity sections format:**

- **People**: detected speakers / guests / referenced people. Plain list, no avatars.
- **Books / things mentioned**: titles, products, places, services that were called out. Plain list.
- **Links**: explicit URLs the host(s) said aloud (e.g. "go to example dot com"). The model returns canonicalised URLs; tap opens in browser.

Empty subsections are hidden, not shown empty.

### Settings → AI features (entry / management)

A new **AI features (optional)** section in Settings with one row:

- Disconnected state: row reads "Connect Gemini API key", subtext "Optional. Enables on-device episode summaries."
- Connected state: row reads "Gemini connected", subtext "Model: Flash · Tap to manage".

Tapping opens a dedicated **AI Setup** screen with:

1. **Disclosure card** — three short paragraphs the user must scroll past. Final paragraph in bold: "Don't paste sensitive content into AI features. On Google's free tier your prompts may be used to improve Google's models."
2. **Get key** — link button: "Get a free Gemini API key" → opens `https://aistudio.google.com/app/apikey` in browser.
3. **Paste field** — secure text input, monospace font, autocapitalise off, autocorrect off. Validation runs a 1-token test request on save and rejects unusable keys with a specific error.
4. **Model picker** — `Flash` (default, recommended) / `Flash-Lite` (faster, lower quality). No Pro option (Pro is paid as of April 2026 — out of free-tier scope).
5. **Connect / Disconnect** buttons. Disconnect wipes the key and any cached summaries on confirm.

### Out of scope for v1

- "Ask this episode" Q&A / chat surface — defer to its own slice.
- Smart auto-chapters with timestamps — defer; needs separate validation harness and a different prompt strategy.
- Recommendations from listen history — defer; not audio-bearing, different data flow.
- Per-podcast AI prefs (auto-summarise on download, etc.) — defer.
- Background batch summarisation — v1 generates on demand only.
- iOS implementation of the secure key store — `expect`/`actual` stub only; iOS compile must stay green but iOS users can't configure a key.
- Sharing/exporting summaries.
- Token/quota counter UI.
- Multi-language summary toggle. Output language follows the device locale via a single prompt instruction; no UI.
- Streaming token-by-token rendering. The summary appears once when ready.

## Privacy & data flow (locked)

1. **Key storage.** The Gemini key is stored only in `EncryptedSharedPreferences` on Android (file: `kofipod_secure`). The file is **excluded from Auto Backup** by adding an `<exclude>` entry to both `backup_rules.xml` and `backup_rules_legacy.xml`. The key never enters SQLDelight, never enters logs, and never appears in `BuildKonfig`.
2. **What leaves the device.** When the user taps Generate: (a) the audio file at `files/downloads/<episode>.mp3` is uploaded to Google's Files API, (b) a single `generateContent` call is made with that file URI plus the prompt, (c) the Files API entry is deleted server-side after the call (or expires in 48h regardless). No metadata about the user, the device, or other episodes is sent.
3. **What we tell the user.** The disclosure card on the Setup screen states: "Audio you summarise is uploaded to Google. Your prompts and responses on Gemini's free tier may be used by Google to improve their models. Don't summarise episodes that contain sensitive content you wouldn't paste into a public AI tool."
4. **Region note.** Users in EEA/UK/CH automatically receive paid-tier privacy on Google's side (no training on inputs). We do **not** detect region or change copy — the disclosure is conservative and applies worldwide.
5. **Logs.** No prompt content, no API responses, no audio bytes, and no portion of the API key are written to any log. Network failures log only the HTTP status code and a short reason.

## Architecture

### New package: `app.kofipod.ai`

All AI code lives under `app/kofipod/ai/`. Detekt's existing `ForbiddenImport` ruleset already blocks Android-only imports from `commonMain`; this package follows the same conventions (`expect`/`actual` for platform pieces).

```
ai/
├── KeyVault.kt              // expect class. Android: EncryptedSharedPreferences; iOS: TODO.
├── GeminiClient.kt          // commonMain. Ktor-based. Files API upload + generateContent.
├── GeminiModels.kt          // enum: Flash, FlashLite. Model IDs + display names.
├── AiPrompts.kt             // single prompt template for v1 episode-summary use case.
├── AiSummaryRepository.kt   // commonMain. Orchestrates: read key → upload → call → persist.
├── AiSummaryDto.kt          // domain types: AiSummary, Person, MentionedThing, MentionedLink.
└── AiError.kt               // sealed class: NoKey, KeyInvalid, RateLimited, AudioTooLong, Network, Unknown.
```

**Why no abstraction over providers in v1.** The KISS reading: one provider, one shape, one prompt. If a second provider ever ships, the seam is "split `GeminiClient` into an interface" — a one-commit refactor. Premature abstractions burn complexity now to save imagined work later.

### KeyVault (expect / actual)

- `commonMain`: `expect class KeyVault { suspend fun get(): String?; suspend fun set(value: String); suspend fun clear() }`.
- `androidMain`: backed by `EncryptedSharedPreferences` (`androidx.security:security-crypto`). Add the dependency to `androidMain` only — it is Android-only and must not leak into `commonMain`. Add `androidx.security.crypto.*` to the detekt forbidden-import list to enforce that.
- `iosMain`: stub returning `null` / no-op for v1. A future slice will use Keychain via `platform.Security.*`.

### Networking

Reuse the existing Ktor `HttpClient` from `data/net/buildHttpClient`. Configure a separate `GeminiHttpClient` with its own JSON config and base URL `https://generativelanguage.googleapis.com/v1beta/`. Auth is `?key=<apiKey>` query param injected at the request level (not at client init), so the same client survives a key rotation without rebuild.

### Files API flow

For an audio file:

1. `POST /upload/v1beta/files?key=…&uploadType=resumable` — start session, get upload URL.
2. `PUT <uploadUrl>` — stream the file body. (Ktor `OutgoingContent.WriteChannelContent` from a `RandomAccessFile`-backed source on Android; v1 uses streaming `readChannel()` over the file path. We are uploading already-downloaded local files only.)
3. `POST /v1beta/models/<model>:generateContent?key=…` — body references the uploaded `fileUri`. One shot, non-streaming for v1.
4. `DELETE /v1beta/files/<id>?key=…` — best-effort cleanup. Ignore failures (server expires in 48h regardless).

Inline upload (`<20MB`) is **not** implemented in v1. Even short podcasts at low bitrate routinely exceed 20MB once you account for prompt overhead.

### Lifecycle

Summary generation runs on the existing **named `"appScope"`** Koin-provided `CoroutineScope` (the SupervisorJob + Default scope already used by `DownloadRepository`). Reuse it; do not introduce a new scope. The job survives screen navigation but does not survive process death. v1 accepts this — if the OS kills the app mid-summary, the user taps Generate again. (A WorkManager-backed reliable variant is on the futures list.)

### Persistence

New SQLDelight table `EpisodeAiSummary.sq` under `composeApp/src/commonMain/sqldelight/app/kofipod/db/`:

```
CREATE TABLE EpisodeAiSummary (
    episodeId       TEXT NOT NULL PRIMARY KEY,
    generatedAtMs   INTEGER NOT NULL,
    modelId         TEXT NOT NULL,
    audioBytes      INTEGER NOT NULL,    -- size of the source file at generation time
    summary         TEXT NOT NULL,        -- markdown body
    peopleJson      TEXT NOT NULL,        -- JSON array of strings
    thingsJson      TEXT NOT NULL,        -- JSON array of strings
    linksJson       TEXT NOT NULL         -- JSON array of {label, url}
);
```

Schema migration: current schema version on disk is **8** (the For You recommendations feature added 7.sqm + 8.sqm via cherry-pick onto this branch). Add `migrations/9.sqm` that creates the new table. Do not edit existing tables.

The `audioBytes` column is the cache-invalidation signal — if the file was redownloaded and its size changed, the cached summary is stale and the UI offers Regenerate. (Hash would be more accurate but materially slower on a 100MB file; size is sufficient for v1.)

### Koin wiring

`di/CommonModule.kt` gains:

- `single { KeyVault(get()) }` (Android needs `Context`)
- `single { GeminiHttpClient() }`
- `single { GeminiClient(get(), get()) }` (HTTP client + key vault)
- `single { AiSummaryRepository(get(), get(), get()) }` (client + DAO + appScope)
- `viewModel { AiSummaryViewModel(get<AiSummaryRepository>(), get<EpisodesRepository>()) }`
- `viewModel { AiSetupViewModel(get<KeyVault>(), get<GeminiClient>()) }`

Per `CLAUDE.md`'s ViewModel-factory rule: any new constructor parameter must be reflected in lockstep here.

## Free-tier reality (locked operating assumptions)

| Concern | Number / behaviour |
|---|---|
| Default model | `gemini-2.5-flash` |
| Audio token rate | 32 tokens/sec ⇒ ~115K tokens/hour |
| Single-request audio ceiling | 9.5 hours hard cap; we soft-cap at **8 hours** in the UI to leave context headroom for prompt + output |
| Files API max upload | 2 GB; in practice limited by the 8h soft cap |
| Free-tier RPD/TPM | ~250 requests/day, ~250K tokens/min (per Google project, not per key) |
| Episodes > 8 h | Show a disabled Generate button with copy: "This episode is too long for AI summary in v1." Defer chunking to a later slice. |

## Error UX (locked)

| Error | Message |
|---|---|
| `NoKey` | "Set up your Gemini key in Settings → AI features." (links to Setup screen) |
| `KeyInvalid` | "Your Gemini key was rejected. Update it in Settings." (links to Setup screen) |
| `RateLimited` | "Your Gemini key is rate-limited. Try again in a few minutes." |
| `AudioTooLong` | "This episode is too long for AI summary." |
| `Network` | "Couldn't reach Google. Check your connection." |
| `Unknown` | "AI summary failed. Tap to retry." |

No error message ever blames Kofipod for what is in fact a user-key issue. No error message ever shows raw HTTP bodies or stack traces.

## Locked vs. open

**Locked (do not redesign):**

- BYOK only. No server-side keys, no proxy, no managed tier.
- One v1 feature: episode summary + entities. Defer Q&A, chapters, recommendations.
- Default model `gemini-2.5-flash`; Flash-Lite alt; no Pro.
- Disclosure copy is mandatory and worldwide; no region detection.
- Key in `EncryptedSharedPreferences`, excluded from Auto Backup, never logged.
- Files API path; no inline.
- 8-hour soft cap.
- Cache by `episodeId` keyed on `audioBytes`; surface Regenerate when stale.
- Schema version bump to 6 via a new `6.sqm`.
- Job runs on existing `"appScope"`; no new scope.
- Disconnect wipes both key and cached summaries.
- iOS: stub `KeyVault` only. Compile must stay green.

**Open (designer's call):**

- Visual treatment of the AI panel on the episode detail screen — card vs. inline section, divider style, density.
- Visual treatment of the entity lists (chips, bulleted text, grouped rows).
- Progress indicator style during `Generating` (linear bar, three-dot, custom).
- Iconography for the AI panel and the Settings entry row.
- Setup screen layout: single scroll, sectioned card, or stepped flow. Disclosure card visual.
- Empty-state visuals where applicable.
- Whether the model name in the Ready footer is plain text or a small chip.
- Light/dark theming within the existing `KofipodTheme` tokens.
- Whether to add tasteful motion when a summary first appears (kept tasteful, no confetti).

## Heads-up for the implementation plan (not a design directive)

- New dependency: `androidx.security:security-crypto` — `androidMain` only; add `androidx.security.crypto.*` to `config/detekt/detekt.yml` `ForbiddenImport` list.
- Bump SQLDelight schema version: 8 → 9; add `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/9.sqm`. CLAUDE.md is already at 8 (separate commit) — Slice 2 only nudges the line to 9.
- Update `backup_rules.xml` and `backup_rules_legacy.xml` to exclude the `kofipod_secure` shared-prefs file by name.
- Koin `viewModel { ... }` factories per `CLAUDE.md` — keep positional arity in lockstep.
- Verify `./gradlew :composeApp:compileKotlinIosSimulatorArm64` after every commit that touches `commonMain` per `CLAUDE.md`.
- Tests in v1 scope: a Paparazzi snapshot of the AI panel in each of its five states (with fake state — no real network), and a unit test for `AiPrompts` JSON shape parsing on a recorded fixture response. No emulator-driven tests against the live API.

## Slice plan (suggested for the implementation doc)

1. **Slice 1 — Settings entry only.** Setup screen, `KeyVault` Android impl + iOS stub, key validation request, disclosure copy. No episode UI yet. Verifiable: paste a real key, see "Connected".
2. **Slice 2 — Episode summary panel, happy path.** New table + migration, `GeminiClient`, `AiSummaryRepository`, the panel in Idle/Generating/Ready states. No entity extraction yet — just summary text. Verifiable on emulator: Generate on a downloaded episode, get summary back.
3. **Slice 3 — Entity extraction.** Extend the prompt + DTO + UI to render People / Things / Links. Cache invalidation via `audioBytes`.
4. **Slice 4 — Error states + Disconnect path.** Wire all six error messages; Disconnect wipes key + summaries.
5. **Slice 5 — Paparazzi baselines + detekt forbidden-import update.** Snapshot the panel in all five states light/dark; add `androidx.security.crypto.*` to the forbidden list.

Each slice ends green: compile + ktlintFormat + detekt + unit tests + iOS simulator-arm64 compile + emulator interaction where the slice has a UI surface.
