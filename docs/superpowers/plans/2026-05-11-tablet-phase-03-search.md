# Phase 3 — Search

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §5 (Search row), §6 (Search bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — sections "Search · master-detail across orientations", "Search · split master-detail", "Search · first run", and the four "Search · {size}" mocks.
**Depends on:** Phase 1, Phase 2 (`MasterDetailPane`, `EmptyDetailHint`).
**Scope:** layout adaptation of `screens/search` only. **No new features:** search, starter packs, scopes (Top / Shows / Episodes / People) all exist already.

## What the mocks show

- Search input row with current query "long form interviews".
- Scope tabs: `Top / Shows / Episodes / People` (existing).
- Results column: list of podcasts with avatar, title, host · category.
- Detail pane (landscape only): focused podcast — large header (avatar, title, "Maggie Pereira · weekly · est. 2019"), `Subscribe` and `Latest` buttons, blurb, "Latest episodes" list.
- 10" portrait: single column with no detail pane (preview shown by tapping → navigate to `PodcastDetail`).
- 8" portrait: single column, no detail pane (current phone behavior).
- 8" landscape: split master-detail (same as 10" landscape, narrower).
- **First run** ("Search · first run"): when query is empty and no recent searches, show "First sip — let's find one" hero + starter packs cluster (Slow news / Maker talk / Field notes) with `Preview` buttons. **Starter packs already exist** — this is layout only.

## Tasks

### 3.1 Extract `SearchContent(state, onEvent, size)`
- Same refactor pattern as Library. Phone branch renders today's layout.

### 3.2 Tablet single-column (8" portrait, 10" portrait)
- New `SearchContentTabletSingle`:
  - Header "Search · Podcasts, episodes, people" + scope tabs.
  - Results: existing list, no avatar size changes.
  - First-run: starter packs rendered as a horizontal scroll of cards. Card composable extracted into `StarterPackCard` for reuse.
- **Tests:** Paparazzi at 800×1200 and 1000×1400.

### 3.3 Tablet master-detail (8" landscape, 10" landscape)
- New `SearchContentTabletMasterDetail`:
  - Master (left, ~46% width): scope tabs + results column.
  - Detail (right, ~54% width): `SearchPreviewPane(podcastId)` — header card + Subscribe/Latest buttons + blurb + last 4 episodes. **Reuses `PodcastRepository.getPodcast(id)` + `observeEpisodes(podcastId).take(4)`** — no new repo methods.
- Empty detail: "Search to find shows" hint when query empty; "Pick a result to preview" when results present but nothing selected.
- Selection: VM-local `selectedSearchResultId: StateFlow<String?>`.
- **Tests:** Paparazzi at 1200×800 and 1400×1000.

### 3.4 Routing
- Phone, tablet portraits: result tap navigates to `Route.PodcastDetail` (today's behavior).
- Tablet landscapes: result tap sets selection; the detail pane's `Latest` button navigates to `Route.PodcastDetail`. Subscribe button calls existing `SubscriptionRepository.subscribe(...)`.

### 3.5 First-run starter-pack tile layout — **DEFERRED**

The original plan assumed starter-pack data and a `StarterPackCard` were available for reuse on the Search side. Implementation revealed starter packs exist as a **full screen** in Library only (`ui/screens/library/StarterPackScreen.kt` + `StarterPackViewModel.kt`), not as a reusable card-level surface. Lifting them into Search first-run would require:

1. Extracting a shared `StarterPackCluster` composable from `StarterPackScreen`, **and**
2. Wiring `StarterPackViewModel` (or a slice of it) into `SearchScreen`'s Koin factory and DI module.

Both are new-feature work, not layout adaptation, so this task is **out of scope** for the tablet-layout effort. The decision is recorded in code at `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/search/SearchScreen.kt:947` (see TODO(tablet-design) above `SearchEmptyState`). Today's first-run cold-state (hero + categories + recents) renders unchanged at all tablet sizes — covered by the Paparazzi baselines in tasks 3.1–3.4.

If product later confirms the design intent, file a follow-up plan covering the shared composable extraction and the VM wiring.

## Acceptance

- All four tablet sizes match the corresponding mocks.
- Phone Paparazzi unchanged.
- Pixel Tablet AVD rotation flow: in 10" landscape, type a query (e.g., "long form interviews"), wait for results, tap one → preview pane updates. Rotate to portrait → in-progress query text and results scroll position survive; selection collapses to a forward `Route.PodcastDetail` entry. Rotate back → previous selection is restored in the right pane. Verify first-run state also survives rotation (starter packs reposition from horizontal scroll to detail-pane occupant).
- Lint / type / iOS / paparazzi green.

## Out of scope

- Any change to Podcast Index API calls or search ranking.
- New starter-pack content (the existing data set is authoritative).
- People scope changes (the design shows the same People tab as today).
