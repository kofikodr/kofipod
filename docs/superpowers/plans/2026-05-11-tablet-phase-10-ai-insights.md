# Phase 10 — AI insights (Summary / Mentioned / Discuss)

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §6 (AI insights bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — section "Episode detail · AI redesign (V3)" (AI SUMMARY card + Mentioned cluster + Ask Gemini composer-stub) and matching "Episode · AI · {size}" mocks; the AskGemini full-screen chat is also referenced ("Ask Gemini about this episode…").
**Depends on:** Phase 9 (host layout extracted), Phase 8 (tabs composables extracted).
**Scope:** tablet-width adaptation of the existing AI content cards inside the Summary / Mentioned / Discuss tabs, plus max-width centering for the AskGemini full-screen route. **No new AI features.**

## What the mocks show

- AI SUMMARY card: header "AI SUMMARY · GEMINI · 3s", subtext "Generated 2m ago · Regenerate", body paragraph.
- Mentioned cluster (when on Mentioned tab — but also previewed under Overview in the V3 mock): 2-column grid of entity cards (PERSON / TOOL / PAPER / BOOK / PROJECT / TERM), each with title + subtitle + optional "mentioned · MM:SS" timestamp.
- "Ask Gemini about this episode…" composer-stub row above the bottom mini-player, with **Skim mode** and **For my notes** chips.
- AskGemini full-screen (separate route, already exists): unchanged behavior; tablet variant centers the chat thread with a max content width.

## Tasks

### 10.1 Summary card width
- The existing `AiSummaryCard` composable renders inside the Summary tab. On tablet, wrap it in a column with max width 720 dp on 10" sizes; full width on 8". No changes to the card itself.
- **Tests:** Paparazzi for Summary tab at 4 tablet sizes — Ready state, Loading state, Error state (one of each per spec acceptance §2).

### 10.2 Mentioned grid
- The existing `EpisodeMentioned` composable currently shows one filtered section at a time on phone (People / Books·things / Links). On tablet:
  - 8" portrait: unchanged (single column, one filtered section).
  - 8" landscape, 10" portrait: 2-column `LazyVerticalGrid` with `GridCells.Fixed(2)`, showing the current filter's entities.
  - 10" landscape: 3-column grid (matches the "Episode · AI · 10" portrait" mock-equivalent at wider width — note the V3 mock's preview cluster shows 6 cards in a 2×3 arrangement; on actual 10" landscape with the rail present this collapses to 3 columns in the Mentioned tab body).
- Card composable unchanged; only the parent layout changes.
- **Tests:** Paparazzi for each filter at 1000×1400 and 1400×1000.

### 10.3 Discuss tab — idle and active states
- Idle state (no messages): existing suggestion + composer-stub. Tablet adds horizontal padding so the suggestion card doesn't stretch — max width 720 dp.
- Active state (continue your chat): existing "Continue your chat" card. Same max width.
- Tapping the composer-stub or `Skim mode` / `For my notes` chips navigates to `Route.AskGemini(episodeId)` — today's behavior. **No new chip behavior.**

### 10.4 AskGemini full-screen route
- The chat thread renders centered with max width 760 dp on 10" sizes and 640 dp on 8". The composer at the bottom inherits the same column width. Rail remains visible (this is a tablet route, not a Player full-bleed).
- Audio-chat quota warning banner unchanged.
- **Tests:** Paparazzi at 4 tablet sizes with a 3-turn fake conversation.

### 10.5 Composer-stub row in Episode detail (when AI tabs visible)
- The composer-stub is a thin row above the docked mini-player on phone. On tablet, it sits at the bottom of the tab body for the Summary / Mentioned / Discuss tabs (per the V3 mock). Wrap it in the same max-width column as the cards above.
- **Tests:** Paparazzi confirms the stub appears on Summary, Mentioned, Discuss tabs; absent on Overview, Chapters, Transcript.

## Acceptance

- Paparazzi: Summary (3 states × 4 sizes = 12), Mentioned (3 filters × 4 sizes = 12), Discuss (idle + active × 4 = 8), AskGemini (4). Plus baselines confirming the composer-stub appears/disappears correctly per tab.
- Phone Paparazzi unchanged across every AI-related screen.
- Pixel Tablet AVD rotation flow with Gemini key configured: open an episode with a cached summary on 10" portrait, navigate to Mentioned (2-col grid). Rotate to landscape — grid becomes 3-col on 10" landscape, scroll position survives. Switch to Discuss, tap composer-stub → AskGemini opens with max-width centered thread. Type a half-finished message, rotate portrait ↔ landscape — in-progress text survives (`rememberSaveable`), thread scroll holds. Send → response renders inside the same max-width column in either orientation.
- Lint / type / iOS / paparazzi green.

## Out of scope

- Any change to Gemini wire format, prompts, schemas, or repos (`AiSummaryRepository`, `DiscussRepository`, `AudioUploadCoordinator`, `GeminiClient`).
- Any new tab.
- Any change to Discuss quota / warning thresholds.
- Disconnect flow changes.
