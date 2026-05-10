# Phase 4 — Downloads

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §6 (Downloads bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — section "Downloads manager" and the four "Downloads · {size}" mocks.
**Depends on:** Phase 1.
**Scope:** layout adaptation of `screens/downloads` only. **No new features:** download states, capacity, Wi-Fi only toggle, "Remove played" all exist already.

## What the mocks show

- Header: "DOWNLOADS" eyebrow, "On device" title, subtitle "6 files · 108 MB · Wi-Fi only".
- Right-aligned `Remove played` action button.
- Capacity bar row: "108 MB on device · of 2.4 GB cap", with a `WI-FI ONLY` chip on the right.
- **Active** section header + rows with state badges (PROGRESS / QUEUED / PAUSED) — each row: avatar, title, show · duration · size.
- **Completed** section header + rows with `DONE` badge.
- Single column in every form factor (no detail pane).

## Tasks

### 4.1 Extract `DownloadsContent(state, onEvent, size)`
- Standard refactor. Phone branch unchanged.

### 4.2 Tablet single-column layout (all 4 sizes)
- New `DownloadsContentTablet`:
  - Header row with title + subtitle on the left, `Remove played` button on the right (button stays the existing IconButton + label).
  - Capacity bar row with the `WI-FI ONLY` chip — existing capacity bar composable resized.
  - Active section: existing `DownloadRow` reused; row max-width capped at 720 dp on 10" landscape so the list doesn't sprawl.
  - Completed section: same.
- Width caps:
  - 8" portrait: full width.
  - 8" landscape, 10" portrait, 10" landscape: content column max 800 dp, horizontally centered, to keep row line lengths comfortable.
- **Tests:** Paparazzi at all four tablet sizes matching the design's "Downloads · {size}" mocks.

### 4.3 Tap behavior
- Tapping a row navigates to `Route.EpisodeDetail(episodeId)` — same as phone. No tablet master-detail on this screen (per spec §6 Downloads bullet and design — no right-pane in any of the four mocks).

## Acceptance

- Four tablet Paparazzi baselines match the mocks.
- Phone Paparazzi unchanged.
- Pixel Tablet AVD: start a download, see PROGRESS row update; pause it, badge flips to PAUSED; complete it, row moves to Completed section. **Rotation flow:** scroll mid-list, rotate portrait ↔ landscape — scroll position and any active progress row stay in sync without flicker. Layout reflows the max-width content column on landscape and uses full-width on 8" portrait.
- Lint / type / iOS / paparazzi green.

## Out of scope

- Download engine changes.
- New badges or states.
- Wi-Fi rule changes.
