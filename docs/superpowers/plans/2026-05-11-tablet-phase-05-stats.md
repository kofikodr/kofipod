# Phase 5 — Stats

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §3 (Stats promoted to rail — already wired in Phase 1), §6 (Stats bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — section "Stats · Pour Over tier" and the "Stats · {size}" mocks.
**Depends on:** Phase 1 (rail destination for Stats is wired there).
**Scope:** layout adaptation of `screens/stats` only. **No new features:** tier system, KPIs, by-show breakdown, daily streak all exist already.

## What the mocks show

- Eyebrow "LISTENING · APR", title "Pour Over", subtitle "Tier 3 of 6 · 14 days in · keep it slow", April month picker, tier chip "3".
- Tier card: "TIER 3 · POUR OVER · You sip, you don't slam · You've kept a calm pace for two weeks running. Next tier French Press unlocks in 6 days."
- Three KPI cards in a row: TOTAL · APR (21h 14m / Across 6 shows), EPISODES (34 / 28 finished · 82%), TIME SKIPPED (Pro · 3h 02m / 14% of total time / contextual blurb).
- DAILY STREAK card: 14d, subtitle "Quiet days count".
- "By show · this month" list with per-show duration and bar.
- Single column at all sizes.

## Tasks

### 5.1 Extract `StatsContent(state, onEvent, size)`
- Standard refactor. Phone branch unchanged.

### 5.2 Tablet layout (all 4 sizes)
- New `StatsContentTablet`:
  - Header block with title + tier chip on the right and month picker.
  - Tier card: existing card reused at full content width.
  - KPI row: `FlowRow` of three KPI cards, each min 280 dp. Wraps to two rows on 8" portrait (matches the "Stats · 8" portrait" mock which stacks them vertically).
  - Daily streak card: full width on 8" portrait, fills remaining row on landscapes.
  - "By show · this month" list: existing rows, max-width 800 dp on 10" landscape to keep bars readable.
- **Tests:** Paparazzi at the four canonical sizes.

### 5.3 Rail entry verification
- Stats appears as 4th rail item per spec §3. Already wired in Phase 1. This phase confirms the screen renders correctly when reached via the rail.
- **Tests:** Compose integration test: with `LocalTabletSize = Tablet10Land`, click rail item Stats, assert `StatsContent` is composed.

## Acceptance

- Four tablet Paparazzi baselines match the mocks.
- Phone Paparazzi unchanged (Stats screen reachable from phone via existing deep-link / Settings entry — not from a phone tab).
- Pixel Tablet AVD: tap Stats in rail, verify the tier card, KPIs, daily streak, and by-show list render. **Rotation flow:** in 8" portrait the KPI row stacks vertically; rotating to 8" landscape lays them side-by-side via `FlowRow`. In 10" the row stays horizontal both ways but width caps adjust. Selected month picker value must survive rotation (`rememberSaveable`).
- Lint / type / iOS / paparazzi green.

## Out of scope

- New stats, new tiers, new KPI categories.
- Any change to the tier unlock logic.
- "Want a 'skip ad' pattern?" call-to-action is presentational only — same as today.
