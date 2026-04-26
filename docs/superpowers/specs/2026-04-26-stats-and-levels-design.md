# Stats & Levels — Design Spec

**Date:** 2026-04-26
**Status:** Design locked, awaiting implementation plan
**Audience:** Design agent (visual/UX) → implementation plan

## Goal & tone

Give the user a quiet **insights-first** view of how they use Kofipod. Levels are flavor on top, not a habit-engineering mechanic. Nothing is in the user's face — no push notifications, no toasts, no nags. The feature reveals itself only when the user goes looking for it (with a single subtle cue when something changes).

## Placement

- New **Stats** destination, reached by a small icon in the **Library screen header** (top-right area).
- Not in the bottom nav. Not in Settings.
- A small dot appears on the icon when the user's tier has changed (up or down) since their last visit to the Stats screen. The dot clears the moment they open the screen.
- Tapping the icon opens a full-screen Stats destination (same nav model as other top-level destinations).

## Stats screen — content (v1)

The screen mixes all-time totals with last-30-day context where each makes sense. **No time-range toggle in v1** — the layout decides per stat.

1. **Total playback time**
   - All-time total, with secondary readout for last 7d and last 30d.
2. **Daily playback** (last ~30 days)
   - Per-day series. Visual treatment is the designer's call (bar chart, heatmap, or sparkline).
3. **Top podcasts**
   - Top 5 podcasts ranked by total listening time. Tapping a row navigates to that podcast's detail screen.
4. **Episodes completed**
   - Count, all-time and last 30d. "Completed" reuses the player's existing completion threshold (do not invent a new definition).
5. **Listening streak**
   - Consecutive days with ≥ 5 minutes of listening. Show **current streak** and **longest streak**.
6. **Average daily listening (last 30d)**
   - Mean minutes/day over last 30 days. This number drives the level (see below), so it doubles as level-progress context.

The **current level** is shown prominently near the top of the screen alongside the user's progress toward the next tier (see Levels).

## Levels

### Metric

Rolling average of **minutes listened per day, over the last 30 days**. Recomputed at most once per day (e.g., on first app open of the day, or when the Stats screen opens — whichever fires first).

### Tier ladder (7 tiers, coffee-themed)

| Tier | Enter at (min/day, 30d rolling avg) | Drop below (–10% hysteresis) |
|---|---|---|
| Decaf | > 0 | — (floor) |
| Drip | ≥ 10 | < 9 |
| Pour Over | ≥ 25 | < 22.5 |
| Espresso | ≥ 45 | < 40.5 |
| Doppio | ≥ 75 | < 67.5 |
| Cold Brew | ≥ 110 | < 99 |
| Triple Shot | ≥ 150 | < 135 |

### Promotion / regression rules

- **Hysteresis:** to advance to tier *N*, the rolling avg must reach tier *N*'s enter-threshold. To drop out of tier *N*, the rolling avg must fall below tier *N*'s drop-threshold (10% under the enter-threshold of *N*). This prevents flicker between adjacent tiers when a user hovers near a boundary.
- **Hard regress:** the tier shown is always the current computed tier. No "highest ever" badge, no protected promotions, no grace periods beyond the hysteresis above.

### Progress display

Show current tier name, next tier name, and a progress indicator from the user's current rolling avg toward the next tier's enter-threshold. Visual form (ring, bar, numeric readout, or combination) is the designer's call. The raw rolling-avg number is already visible as stat #6.

## Empty / early state

- **Stats screen is reachable from day 1.** The Library header icon is always present.
- **Stats render from day 1** with whatever data exists (totals, today's listening, first podcast, etc.). No artificial "come back later" gate on the stats themselves.
- **Level is suppressed until the user has at least 7 days of usage history** (i.e., 7 distinct calendar days since first listen). Until then, the level area shows a soft placeholder ("Your level will appear after a week of listening" — designer to refine copy and visual). This avoids misleading early spikes (e.g., a 5-min first session reading as a high tier under a short window).

## Tier-change cue

- A single small **dot/badge on the Library header stats icon** when the tier has changed (up or down) since the user last opened the Stats screen.
- The dot clears the next time they open the Stats screen.
- Symmetric for promotions and regressions — no celebratory toast, no shame on regression. The screen itself reflects the new tier.
- **No** push notifications. **No** toasts/snackbars. **No** in-feed banners.

## Out of scope (v1)

- Per-podcast personal stats (your time on a single show) — defer.
- Hour-of-day distribution / "when do you listen" — defer.
- "Time saved by playback speed > 1x" — defer.
- Episode-level "most replayed" — needs telemetry not currently captured.
- Sharing / export of stats. Wrapped-style year-in-review.
- Time-range toggle on the stats screen.
- "Best ever" tier badge or any softening of regression.

## Data requirements (note for the implementation plan)

This is a heads-up for the plan author, not a design directive — the designer can ignore this section.

The six v1 stats and the level metric all roll up from **daily listening minutes per podcast**. The current schema (`PlaybackState`, `Episode`, `Podcast` per `CLAUDE.md`) tracks per-episode position but may not record **per-day listening sessions**. The implementation plan will likely need:

- A new SQLDelight table (e.g., `ListeningSession.sq`) capturing `(date, episodeId, podcastId, secondsListened)` rolled up daily, OR a per-day aggregation derived from session events.
- A schema migration (current schema version is 5 per `CLAUDE.md`; add `6.sqm`).
- A `StatsRepository` that exposes Flows for each of the six stats and a derived `LevelState` (current tier, next tier, progress, whether 7-day gate has cleared).
- Tier computation lives in a pure Kotlin function in `commonMain` so it's trivially unit-testable; hysteresis state is persisted (we need to remember the last-emitted tier to apply the drop-threshold rule).

The designer does not need to make decisions here.

## Designer handoff — what's open vs. locked

**Locked (do not redesign):**
- The 6 stats and what each measures.
- The 7 tier names and thresholds.
- Hysteresis at –10%.
- Hard regression. No "best ever" badge.
- 7-day gate before level appears.
- Library-header entry. Dot cue on tier change.
- No push, no toast, no snackbar.

**Open (designer's call):**
- Visual style of the stats icon in the Library header.
- Layout and visual treatment of the Stats screen (single scroll, sectioned cards, tabs within the screen, etc.).
- Visualization for daily playback (bar / heatmap / sparkline).
- Visual representation of the current tier (badge style, color, iconography per tier).
- Progress-to-next-tier indicator (ring, bar, numeric, combo).
- Empty-state copy and visual when level is suppressed (< 7 days).
- Empty-state copy and visual for individual stats with no data (e.g., zero completed episodes).
- Whether to include any tasteful motion (e.g., progress ring fill on entry) — kept tasteful, no confetti.
- Light/dark theming within the existing `KofipodTheme` tokens.
