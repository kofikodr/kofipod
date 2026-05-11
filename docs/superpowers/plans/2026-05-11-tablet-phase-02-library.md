# Phase 2 — Library

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §5 (Library row), §6 (Library bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — sections "Library · all four form factors", "Library · folders & subscriptions", and the four "Library · {size}" mocks.
**Depends on:** Phase 1 (rail, scaffold, docked mini-player, `LocalTabletSize`).
**Scope:** layout adaptation of `screens/library` only. **No new features:** folders, shelves, and subscriptions all exist already.
**Skills:** `superpowers:test-driven-development`, `superpowers:verification-before-completion`, `superpowers:requesting-code-review`.

## What the mock shows

- Header block: "LIBRARY" eyebrow, "Your shelves" title, subtitle "6 subscriptions · 14 unplayed · synced 2m ago".
- In-library search input row: "Search in library — episodes, transcripts, bookmarks" with a Pro pill on the right.
- "Folders" cluster (existing Pro feature): horizontal row of 4 folder cards (Morning / Long form / Indie / Saved for later), each card shows count + remaining minutes. "Manage" link on the right.
- "Subscriptions" cluster: section header with `Recent · A → Z` sort toggle (already exists); grid of avatar+title cards with NEW badges (3 NEW, 1 NEW, etc.) — matches existing phone subscriptions list, re-laid-out in grid form on tablet.
- Landscape master-detail: right pane shows the selected subscription's recent episodes as a flat list. Empty state when nothing selected.
- 8" portrait drops the "Saved for later" folder card (3 cards instead of 4) — matches the existing folders horizontal-scroll.

## Tasks

### 2.1 Extract `LibraryContent(state, onEvent, size)`
- Refactor `LibraryScreen` into a top-level entry point that grabs the VM + a stateless `LibraryContent` taking `TabletSize?`. Phone branch (`size == null`) renders today's layout unchanged.
- **Tests:** snapshot existing phone Paparazzi baseline; verify it is byte-identical post-refactor.

### 2.2 Tablet single-column layout (8" portrait, 10" portrait)
- New composable `LibraryContentTabletSingle(state, onEvent, size)`:
  - Top: header block + in-library search input row.
  - Folders cluster: horizontal scroll of folder cards. Card width adapts to `size` (320 dp on 10"P, 260 dp on 8"P).
  - Subscriptions cluster: `LazyVerticalGrid` with `GridCells.Adaptive(minSize = 320.dp)` for 10"P, `Adaptive(260.dp)` for 8"P. Card composable reused from phone subscriptions row but rendered as a grid tile.
- **Tests:** Paparazzi at 800×1200 and 1000×1400 matching the design's "Library · 8" portrait" and "Library · 10" portrait" mocks.

### 2.3 Tablet master-detail (8" landscape, 10" landscape)
- New composable `LibraryContentTabletMasterDetail`. Uses `MasterDetailPane` from Phase 1.
- Master (left, ~62% width): the single-column body from 2.2.
- Detail (right, ~38% width): when a subscription is selected, render `SubscriptionPreviewPane(podcastId)` — a thin read-only list of the last N episodes for that podcast. **Source data: the existing `PodcastRepository.observeEpisodes(podcastId).take(N)` — no new repo methods.**
- Empty detail: centered hint "Pick a subscription to preview" using `EmptyDetailHint` from Phase 1.
- Selection state in `LibraryViewModel` as a new `StateFlow<String?> selectedPodcastId` — VM-local UI state, NOT persisted, NOT routed.
- **Tests:** Paparazzi at 1200×800 and 1400×1000 with and without a selection; unit test selection state survives VM lifecycle.

### 2.4 Routing the tile tap
- On phone and on tablet portraits: tap navigates to `Route.PodcastDetail` (today's behavior).
- On tablet landscapes: tap sets `selectedPodcastId` and does NOT navigate. A second tap on the detail pane's "Open" affordance navigates to `Route.PodcastDetail`. This matches the master-detail design — preview, then commit.
- **Tests:** unit test the `onPodcastTap(size, podcastId)` branching logic.

## Acceptance

- All four tablet sizes render per the corresponding design mock.
- Phone Paparazzi baseline unchanged.
- Pixel Tablet AVD rotation flow: in 10" landscape, scroll library to mid-list, tap a subscription → preview updates. Rotate to portrait → preview pane collapses, the in-flight selection becomes a forward nav entry on `Route.PodcastDetail` (back returns to master list with scroll position preserved). Rotate back to landscape → back stack pops the forward entry and the previous selection is restored in the right pane. Repeat on 8" landscape (no expanded rail, but same master-detail behavior).
- Lint / type / iOS compile / paparazzi all green.

## Out of scope

- Folder creation / management UI — "Manage" link still routes to the existing Pro folders screen unchanged.
- Any in-library search behavior — the input is wired to the existing query state; we don't change ranking, indexing, or results.
