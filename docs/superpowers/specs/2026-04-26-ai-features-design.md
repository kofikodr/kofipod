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

A new **AI panel** on the **Episode detail screen** (`ui/screens/detail/EpisodeDetailScreen.kt`), inserted under the description block and above the chapters section. The chapters section is already rendered by the existing detail screen (see `ChaptersRepository`); the AI panel is a sibling composable, not folded into chapter rows or `EpisodeRowData` (preserves the perf invariants in `CLAUDE.md`).

**Source selection.** The repository picks the cheapest sufficient input per episode:

1. **Transcript path** — `episode.transcriptUrl` non-blank. Fetch the raw bytes (no auth, just `HttpClient.get`), pass them verbatim into Gemini as a `text` part along with the prompt. Format detection is the model's job: VTT, SRT, JSON, plain text, anything Podcasting 2.0 publishers ship today — the prompt instructs Gemini to ignore cue numbering, timestamps, and speaker labels and produce clean prose. ~50–200× cheaper than audio for the same episode and orders of magnitude faster.
2. **Audio path** — fallback used when `transcriptUrl` is null/blank. Files API resumable upload, then `generateContent` referencing the uploaded `fileUri`. Same prompt, same output shape. **Slice 2.5** in the implementation plan.

There is no description-only or chapters-only path. If neither transcript nor (eventually) audio is available, the panel surfaces an error rather than producing a low-quality summary from show notes alone.

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
├── AiHttpClient.kt          // expect/actual. Dedicated key-bearing client; no Logging plugin.
├── GeminiClient.kt          // commonMain. Ktor-based. text + audio generateContent paths.
├── GeminiModels.kt          // enum: Flash, FlashLite. Model IDs + display names.
├── AiPrompts.kt             // single prompt template for v1 episode-summary use case.
├── AiSummaryRepository.kt   // commonMain. Source selection (transcript→audio) + persist.
├── AiSummaryDto.kt          // domain types: AiSummary, MentionedLink, AiSourceKind.
└── AiError.kt               // sealed class: NoKey, KeyInvalid, RateLimited, AudioTooLong, Network, TranscriptUnavailable, Unknown.
```

**Why no abstraction over providers in v1.** The KISS reading: one provider, one shape, one prompt. If a second provider ever ships, the seam is "split `GeminiClient` into an interface" — a one-commit refactor. Premature abstractions burn complexity now to save imagined work later.

### KeyVault (expect / actual)

- `commonMain`: `expect class KeyVault { suspend fun get(): String?; suspend fun set(value: String); suspend fun clear() }`.
- `androidMain`: backed by `EncryptedSharedPreferences` (`androidx.security:security-crypto`). Add the dependency to `androidMain` only — it is Android-only and must not leak into `commonMain`. Add `androidx.security.crypto.*` to the detekt forbidden-import list to enforce that.
- `iosMain`: stub returning `null` / no-op for v1. A future slice will use Keychain via `platform.Security.*`.

### Networking

Two distinct HTTP clients — never share:

- `data/net/buildHttpClient` — the existing app-wide client. Used to **fetch transcript bytes** (`HttpClient.get(transcriptUrl).bodyAsText()`). No auth, no API key, public URLs only.
- `ai/AiHttpClient` — dedicated key-bearing client (already in tree). **No `Logging` plugin** so the `?key=` query param can never reach a sink. Base URL `https://generativelanguage.googleapis.com`. Auth is `?key=<apiKey>` injected at the request level so the same client survives a key rotation without rebuild.

### Source selection (transcript path → audio fallback)

Per episode, `AiSummaryRepository.generate()` picks the cheapest available input:

**Transcript path (Slice 2)** — when `episode.transcriptUrl` is non-blank:

1. `GET <transcriptUrl>` via the app-wide `HttpClient`. Read body as text.
2. `POST /v1beta/models/<model>:generateContent?key=…` with two `parts`: `{text: <prompt>}` and `{text: <transcript body>}`. The prompt instructs Gemini to ignore cue numbers, timestamps, and speaker prefixes and to produce clean prose — format detection (VTT/SRT/JSON/plain) is the model's job, not ours.
3. Persist with `sourceKind = 'transcript'`, `sourceFingerprint = transcriptUrl`.

A transcript fetch that returns non-2xx surfaces `AiError.TranscriptUnavailable` (offers retry; does not auto-fall-back to the audio path — that's a v2 decision and risks silent quota burn).

**Audio path (Slice 2.5)** — when `transcriptUrl` is null/blank, and the user has the episode downloaded:

1. `POST /upload/v1beta/files?key=…&uploadType=resumable` — start session, get `X-Goog-Upload-URL`.
2. `PUT <uploadUrl>` — stream the file body via Ktor `OutgoingContent.WriteChannelContent` from a path-backed source. We only upload already-downloaded local files; if the episode isn't downloaded the panel shows a single-line hint ("Download this episode to summarise it.") and disables Generate.
3. Poll `GET /v1beta/files/<name>?key=…` until `state == "ACTIVE"` (cap 30s).
4. `POST /v1beta/models/<model>:generateContent?key=…` — body references the uploaded `fileUri`. Persist with `sourceKind = 'audio'`, `sourceFingerprint = <byte count>`.
5. `DELETE /v1beta/files/<name>?key=…` — best-effort cleanup. Server expires in 48h regardless.

Inline upload (`<20MB`) is **not** implemented in v1.

### Lifecycle

Summary generation runs on the existing **named `"appScope"`** Koin-provided `CoroutineScope` (the SupervisorJob + Default scope already used by `DownloadRepository`). Reuse it; do not introduce a new scope. The job survives screen navigation but does not survive process death. v1 accepts this — if the OS kills the app mid-summary, the user taps Generate again. (A WorkManager-backed reliable variant is on the futures list.)

### Persistence

New SQLDelight table `EpisodeAiSummary.sq` under `composeApp/src/commonMain/sqldelight/app/kofipod/db/`:

```
CREATE TABLE EpisodeAiSummary (
    episodeId         TEXT NOT NULL PRIMARY KEY,
    generatedAtMs     INTEGER NOT NULL,
    modelId           TEXT NOT NULL,
    sourceKind        TEXT NOT NULL,        -- 'transcript' | 'audio'
    sourceFingerprint TEXT NOT NULL,        -- transcript: the URL; audio: byte count as string
    summary           TEXT NOT NULL,        -- markdown body
    peopleJson        TEXT NOT NULL DEFAULT '[]',  -- populated in Slice 3
    thingsJson        TEXT NOT NULL DEFAULT '[]',  -- populated in Slice 3
    linksJson         TEXT NOT NULL DEFAULT '[]'   -- populated in Slice 3
);
```

Schema migration: current schema version on disk is **11** after rebasing onto master (the episode-detail Slice 4 work added `9.sqm` for Episode chapters/transcript URLs, `10.sqm` for `EpisodeChapter`, and `11.sqm` for `Podcast.primaryCategory`). Add `migrations/12.sqm` that creates the new AI table. Do not edit existing tables.

The `(sourceKind, sourceFingerprint)` pair is the cache-invalidation signal. If the cached row's `sourceKind` differs from the currently best-available source for the episode (e.g. publisher added a transcript after we summarised the audio), or the fingerprint differs (transcript URL changed; the audio file was redownloaded with a different byte count), the UI surfaces Regenerate. Hash-based fingerprints would be more accurate but materially slower on a 100MB audio file; for v1, URL-equality and byte-count equality are sufficient.

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
| `TranscriptUnavailable` | "Couldn't fetch the transcript. Try again, or wait — the publisher may be having a moment." |
| `Unknown` | "AI summary failed. Tap to retry." |

No error message ever blames Kofipod for what is in fact a user-key issue. No error message ever shows raw HTTP bodies or stack traces.

## Locked vs. open

**Locked (do not redesign):**

- BYOK only. No server-side keys, no proxy, no managed tier.
- One v1 feature: episode summary + entities. Defer Q&A, chapters, recommendations.
- Default model `gemini-2.5-flash`; Flash-Lite alt; no Pro.
- Disclosure copy is mandatory and worldwide; no region detection.
- Key in `EncryptedSharedPreferences`, excluded from Auto Backup, never logged.
- Source ladder: transcript (Slice 2) → audio Files API (Slice 2.5). No description-only or chapters-only path. No runtime auto-fall-back from transcript to audio (failed transcript fetch surfaces an error, not a silent quota burn).
- Files API path for audio; no inline upload.
- 8-hour soft cap (audio path only — transcripts have no comparable token-rate ceiling but the same 1M-token model context).
- Cache key is `(sourceKind, sourceFingerprint)`; surface Regenerate when either differs from current best-available source.
- Schema migration `12.sqm` adds `EpisodeAiSummary` (current schema is 11 after the episode-detail Slice 4 work added 9–11.sqm).
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
- Bump SQLDelight schema version: 11 → 12; add `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/12.sqm`. CLAUDE.md is already at 11 (master after episode-detail Slice 4) — Slice 2 only nudges the line to 12.
- Update `backup_rules.xml` and `backup_rules_legacy.xml` to exclude the `kofipod_secure` shared-prefs file by name.
- Koin `viewModel { ... }` factories per `CLAUDE.md` — keep positional arity in lockstep.
- Verify `./gradlew :composeApp:compileKotlinIosSimulatorArm64` after every commit that touches `commonMain` per `CLAUDE.md`.
- Tests in v1 scope: a Paparazzi snapshot of the AI panel in each of its five states (with fake state — no real network), and a unit test for `AiPrompts` JSON shape parsing on a recorded fixture response. No emulator-driven tests against the live API.

## Slice plan (suggested for the implementation doc)

1. **Slice 1 — Settings entry only.** Setup screen, `KeyVault` Android impl + iOS stub, key validation request, disclosure copy. **Done.**
2. **Slice 2 — Episode summary panel, transcript path.** New table + migration, `GeminiClient.generateFromText`, transcript fetch via the app-wide `HttpClient`, `AiSummaryRepository` with source-selection (transcript-only in this slice), panel in Idle/Generating/Ready states embedded in `EpisodeDetailScreen`. No entity extraction yet — summary text only. Verifiable on emulator: open an episode whose feed ships a transcript, tap Generate, see the summary in seconds.
3. **Slice 2.5 — Audio fallback.** Files API resumable upload, audio `generateContent`, polling for `ACTIVE`, best-effort `deleteFile`. Repository's source selector now picks audio when `transcriptUrl` is null. Same panel; no UI changes beyond the disabled-Generate hint when the episode is not yet downloaded.
4. **Slice 3 — Entity extraction.** Extend the prompt to ask for structured JSON (`responseMimeType: application/json` + `responseSchema`); UI renders People / Things / Links sections under the summary.
5. **Slice 4 — Error states + Disconnect path.** Wire all error messages (including `TranscriptUnavailable`); Disconnect wipes key + summaries.
6. **Slice 5 — Paparazzi baselines + final hardening.** Snapshot the panel in each state, light/dark.

Each slice ends green: compile + ktlintFormat + detekt + unit tests + iOS simulator-arm64 compile + emulator interaction where the slice has a UI surface.
