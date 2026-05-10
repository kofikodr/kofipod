# Phase 9 — Episode detail (V3 layout)

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §6 (Episode detail bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — section "Episode detail · AI redesign (V3)" and the "Episode · AI · {size}" mocks at 10" portrait, 8" landscape, 8" portrait.
**Depends on:** Phase 1, Phase 8 (the Episode tabs composables are extracted in Phase 8 for the right pane).
**Scope:** layout adaptation of `Route.EpisodeDetail` (the standalone full-screen route) at tablet sizes. **No new tabs, no new content surfaces.** AI content (Summary / Mentioned / Discuss) is owned by Phase 10.

## What the mocks show

- Header: "SIGNAL & NOISE · EP 214" eyebrow, big title "A short history of developer tooling".
- Tab strip: **Overview / Chapters / Summary / Mentioned · 12 / Discuss / Transcript** — current tab set, no additions.
- Overview body: blurb paragraph.
- Below tabs (still on the "Overview" tab): the V3 AI summary card and Mentioned preview cluster — these are existing components rendered at tablet width.
- Bottom area: "Ask Gemini about this episode…" composer-stub row with **Skim mode** and **For my notes** chips. (Owned by Phase 10.)

## Tasks

### 9.1 Extract `EpisodeDetailContent(state, onEvent, size)`
- Refactor the existing `EpisodeDetailScreen` into a stateless content composable. Phone branch unchanged.

### 9.2 Tablet single-screen layout (8" portrait, 8" landscape standalone-context, 10" portrait, 10" landscape standalone-context)
- "Standalone-context" = `Route.EpisodeDetail` opened directly (e.g., from Downloads, from a deep link, from Stats), NOT from Podcast detail's master-detail.
- Layout:
  - Centered column, max width 880 dp on 10" landscape and 760 dp on 10" portrait, full width on 8" sizes.
  - Header block + tab strip at top.
  - Tab body: existing per-tab composables (`EpisodeOverview`, `EpisodeChapters`, `EpisodeSummary`, `EpisodeMentioned`, `EpisodeDiscuss`, `EpisodeTranscript`) reused without modification.
- **Tests:** Paparazzi at 800×1200, 1200×800, 1000×1400, 1400×1000 on the **Overview** tab and the **Chapters** tab (Summary / Mentioned / Discuss are covered in Phase 10).

### 9.3 Master-detail-right-pane mode
- When this composable is hosted as the right pane of Podcast detail (Phase 8.3), it renders without the standalone-context header / max-width — the host provides those constraints. Add a `hostMode: HostMode` parameter (`Standalone | MasterDetailPane`) defaulting to `Standalone`. Phase 8 passes `MasterDetailPane`.
- **Tests:** snapshot in `MasterDetailPane` mode and confirm there's no double-wrapping.

### 9.4 Tab strip behavior
- Tab strip stays scrollable horizontally on 8" portrait (matches the existing phone tab strip behavior since the labels are long).
- 10" sizes show all tabs without scrolling.

## Acceptance

- 8 Paparazzi snapshots added (4 sizes × 2 tabs).
- Phone Paparazzi unchanged.
- Pixel Tablet AVD rotation flow: open an episode from Downloads (standalone context) on 10" portrait. Tab to Chapters, scroll mid-list, rotate to landscape — tab selection and scroll position survive; max-width cap changes (760 dp → 880 dp) without re-layout flicker. Open the same episode from a podcast's master-detail right pane on 10" landscape, verify it renders in `MasterDetailPane` host mode without duplicate header; rotate to portrait — the episode becomes a forward `Route.EpisodeDetail` standalone entry with the same tab selected.
- Lint / type / iOS / paparazzi green.

## Out of scope

- AI tab bodies (Phase 10).
- Transcript performance / chunking.
- Any change to playback resume logic from the "Resume" affordance.
