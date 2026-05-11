# Phase 8 — Podcast detail

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §5 (Podcast detail row), §6 (Podcast detail bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — section "Podcast detail + episode" and the four "Podcast detail · {size}" mocks.
**Depends on:** Phase 1, Phase 2 patterns (`MasterDetailPane`, `EmptyDetailHint`).
**Scope:** layout adaptation of `screens/detail` (the podcast-detail variant) only. **No new content surfaces.**

## What the mocks show

- Header: large podcast title "Signal & Noise", subtitle "Maggie Pereira · weekly", avatar, "SUBSCRIBED · 214 EP" chip, blurb.
- Filter chips: All / Unplayed / Downloaded (existing).
- Episode list with NEW badge and per-row metadata.
- Right pane (10" landscape, 8" landscape): focused episode card.
  - "Episode 214 · 2h ago" eyebrow, title, "With Alex Eaves · recorded April 19 · 56 min".
  - "Resume · 18:42" button, "56:08 · MP3 · 24 MB" meta.
  - Tabs: Overview / Chapters / Mentioned / Discuss.
  - Body for the selected tab (mock shows Overview blurb + Chapters list with timestamps).

## Tasks

### 8.1 Extract `PodcastDetailContent(state, onEvent, size)`
- Standard refactor of the existing podcast-detail composable. Phone branch unchanged.

### 8.2 Tablet portraits + 8" landscape: single column with header band
- The portrait mocks show a single column with the header / filter chips / episode list. Tap on an episode navigates to `Route.EpisodeDetail` (today's behavior).
- 8" landscape uses master-detail (per the "Podcast detail · 8" landscape" mock) — see 8.3.

### 8.3 10" and 8" landscape: master-detail
- New `PodcastDetailTabletMasterDetail`:
  - Master (left, ~46% width): header block + filter chips + episode list.
  - Detail (right, ~54% width): focused episode card with the 4 internal tabs (Overview / Chapters / Mentioned / Discuss) — **rendered inline** using the same `EpisodeOverview / EpisodeChapters / EpisodeMentioned / EpisodeDiscuss` composables already used on `Route.EpisodeDetail`. **No new VMs** — the existing `EpisodeDetailViewModel` is hoisted on demand keyed by `episodeId` (Koin `getViewModel(key = episodeId, parameters = { parametersOf(episodeId) })`).
- Selection: `selectedEpisodeId: StateFlow<String?>` on `PodcastDetailViewModel`, defaulting to the first episode if available.
- The detail pane's `Resume · MM:SS` button calls the existing `play(episodeId)` flow; "Open" affordance navigates to `Route.EpisodeDetail` for the full screen.
- **Tests:** Paparazzi at 1200×800 and 1400×1000 with a selected episode showing each of the 4 tabs.

### 8.4 Routing
- Phone, tablet portraits: episode tap → `Route.EpisodeDetail`.
- Tablet landscapes: episode tap sets `selectedEpisodeId`; the detail pane's "Open" button navigates to `Route.EpisodeDetail`. Tab selection within the right pane is VM-local — survives selection changes per episode.

### 8.5 Header band wrapping
- The header block (title, blurb) wraps comfortably on 8" portrait. Apply max-width 720 dp on landscapes' master pane so the title doesn't run too wide.

## Acceptance

- Four tablet Paparazzi baselines.
- Phone Paparazzi unchanged.
- Pixel Tablet AVD rotation flow: open Signal & Noise in 10" landscape, scroll episode list, tap a non-first episode → right-pane preview updates, master scroll position holds. Rotate to portrait → preview collapses into a forward `Route.EpisodeDetail` entry (master scroll preserved). Rotate back to landscape → previous episode selection AND right-pane tab selection (Overview / Chapters / Mentioned / Discuss) are restored. Filter chip selection (All / Unplayed / Downloaded) also survives.
- Lint / type / iOS / paparazzi green.

## Out of scope

- Subscribe / unsubscribe flow changes.
- Filter chip behavior changes.
- AI tabs (Summary content lives in `Route.EpisodeDetail`'s tabs — covered by Phases 9 / 10).

## Deferred — tab embedding in landscape detail pane

Task 8.3's original design embedded the full `EpisodeDetailContent(hostMode = MasterDetailPane)` (with Overview / Chapters / Mentioned / Discuss tabs) in the right pane, hoisting a Koin `EpisodeDetailViewModel` per `episodeId`. **This is deferred to a follow-up.** Phase 8 ships the simpler preview-pane variant instead — same shape as Library's `SubscriptionPreviewPane` and Search's preview pane — to keep Phase 8 scoped to layout adaptation and avoid the risk of per-episode VM hoisting + tab-state rehydration on rotation.

What landed in Phase 8:
- Right pane shows episode metadata only (eyebrow with episode number + date, title, podcast title · duration · size meta line, Play button, "Open" affordance, truncated description).
- "Open" navigates to `Route.EpisodeDetail` for the full tabs experience.
- No `EpisodeDetailViewModel` hoist, no tab state, no chapters / mentioned / discuss surfaces in the right pane.

Follow-up will add the embedded tabs once the rotation-survival story for `EpisodeDetailViewModel` (and its dependent `AiSummaryViewModel`) is designed alongside the AI-tab phases. The selection mechanism (`selectedEpisodeId` on `PodcastDetailViewModel`) is already in place, so the follow-up is additive — replace `EpisodePreviewPane`'s body with the embedded tabs, keep the selection wire-up untouched.
