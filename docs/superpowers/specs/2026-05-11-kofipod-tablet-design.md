# Kofipod tablet design — spec

**Date:** 2026-05-11
**Reference design (authoritative):** `docs/kofipod-tablet-design.html` (rendered locally — self-extracting React bundle, light-only, hi-fi tablet mocks at four breakpoints).
**Out of scope:** any new feature, screen, capability, or behavior change. **Tablet layout adaptation only.** The phone build must not regress (binary identical UI on phone form factors).

**Orientation policy.** Unlike phone (portrait-locked at runtime via `MainActivity.kt:28-33`, `SCREEN_ORIENTATION_PORTRAIT` for `smallestScreenWidthDp < 600`), **tablet supports both portrait and landscape natively.** The same `MainActivity` already passes `SCREEN_ORIENTATION_UNSPECIFIED` when `smallestScreenWidthDp >= 600`, so the OS rotates the activity freely on tablets. This is not a new behavior, but it is the load-bearing assumption behind every "master-detail on landscape, single column on portrait" decision in this spec. **Every tablet screen must render correctly in BOTH orientations and must survive rotation without losing user-visible state** (selection in master-detail, scroll position in lists, tab selection in tab strips, in-progress text in inputs).

This spec is the contract for the implementation plans under `docs/superpowers/plans/2026-05-11-tablet-phase-*.md`. Every phase plan references this document by section.

---

## 1. Intent (one paragraph)

Adapt the existing Kofipod Compose UI so it renders correctly on Android tablets without changing what the app does. Phone is portrait-locked and untouched. Tablet introduces a left navigation rail (replacing the phone's bottom tab bar), a persistent docked mini-player at the bottom (replacing the full-screen Player takeover on routine playback), and master-detail layouts where horizontal width allows. The same component tree drives both, parameterized by a single window-size class.

## 2. Form factors and breakpoints

Per the design's opening card ("One responsive UI across four form factors"):

| Form factor       | Dimensions (dp)  | Rail mode             | Content layout                       |
|-------------------|------------------|-----------------------|--------------------------------------|
| 8" portrait       | 800 × 1200       | Icon-only rail        | Single column                        |
| 8" landscape      | 1200 × 800       | Icon-only rail        | Master-detail (two pane)             |
| 10" portrait      | 1000 × 1400      | Icon + label rail     | Single column                        |
| 10" landscape     | 1400 × 1000      | Expanded rail (brand header + profile chip at bottom of rail) | Master-detail (two pane) |
| Phone (existing)  | < 600 dp width   | Bottom tab bar (unchanged) | Single column (unchanged)       |

**Single classifier.** A `TabletSize` enum derived from `WindowWidthSizeClass` + `WindowHeightSizeClass` (Material 3 `androidx.compose.material3.adaptive.*` or the equivalent windowsize artifact already vendored). Phone returns `null` / `Phone`; non-null tablet values drive everything below. **One classification site, passed via `CompositionLocal`** — no per-screen `BoxWithConstraints` heuristics.

**Master-detail trigger:** `useMasterDetail = size in { Tablet8Land, Tablet10Land }`. Portraits use single column at both sizes per the design's "8" portrait" and "10" portrait" mocks.

## 3. Navigation rail

Replaces `KofipodBottomBar` whenever `size != null` (tablet). The phone bottom bar code path stays intact and is selected at the same site.

Rail destinations, **in this order** (matching the design's left rail):

1. Library (`Route.Library`)
2. Search (`Route.Search`)
3. Downloads (`Route.Downloads`)
4. **Stats** (`Route.Stats`) — promoted from a deep-link to a top-level rail entry. This is a routing change only; the existing `screens/stats` content is unchanged.
5. Settings (`Route.Settings`)

Rail visual states by `TabletSize`:

- **Icon-only** (8" portrait, 8" landscape): 64–72 dp wide, no labels, icon centered, active item gets pink-accent background pill.
- **Icon + label** (10" portrait): ~200 dp wide, icon left of label, active item pill spans the row.
- **Expanded** (10" landscape): ~240 dp wide, includes the **brand block at top** (Kofipod wordmark + logo) and a **profile chip at bottom** (avatar "JM", "James M.", "● Drive" sync status). Brand and profile blocks are presentational stubs that read from existing state (account name, Drive sync status) — no new account flows.

Selection behavior matches the existing `KofipodBottomBar`: pop to start destination of the tab, `launchSingleTop = true`, no animations beyond Compose default.

**Rail hidden when** `currentRoute == Route.Player` (Now Playing remains full-bleed across all four tablet sizes per the design's "Now Playing · full bleed" card).

## 4. Docked mini-player

The pink "Now playing" strip at the bottom of every non-Player screen in every tablet mock. Persistent, single-row, ~72 dp tall, spans content width (NOT under the rail — the rail keeps its own background). Shows: artwork thumbnail, episode title, "Show · Ep N · MM:SS / MM:SS", play/pause, speed chip (`1.4×` in the mock).

Tap target opens the full-screen Player (`Route.Player`). The phone build's existing mini-player remains as-is; tablet uses the same composable with an alternate `size`-aware layout.

Hidden when:
- `currentRoute == Route.Player` (player is full screen)
- No active episode (same rule the phone uses today)
- Onboarding / sign-in (`Route.Onboarding`)

## 5. Master-detail patterns

Each pattern is one screen split into two panes inside the existing route. Selection state lives in the existing ViewModel; deep links land in the same place they do today. No new routes are added.

| Master pane                                | Detail pane                                 | Routes affected         |
|--------------------------------------------|---------------------------------------------|-------------------------|
| Library: shelves + folders + subscriptions | Selected podcast's episode list (read-only preview, NOT podcast detail) — when nothing selected, shows a friendly empty state | `Route.Library` |
| Search: results list + filter chips        | Preview of selected podcast (subscribe CTA + latest episodes), as in the "Search · split master-detail" mock | `Route.Search` |
| Podcast detail: episode list               | Selected episode panel (Overview / Chapters / Mentioned / Discuss tabs) per the "Podcast detail + episode" mock | `Route.PodcastDetail` |

**Empty detail pane:** show a centered low-contrast hint ("Pick a subscription to preview", etc.) — never a blank rectangle. Single shared composable.

**Selection persistence:** master selection survives configuration change (rotation between landscape/portrait — selection state stays in the VM; portrait collapses to single column and pushes the detail as a nested route via the existing navigation, NOT a new screen).

**Rotation behavior:** master-detail collapses to single-column on rotation to portrait (10" only — 8" portrait stays single column anyway). The currently-selected detail is preserved as a forward nav entry so back returns to the master list. Rotation must not drop:
- Master selection (lives in the VM as `StateFlow`, survives configuration change).
- Master scroll position (use `rememberLazyListState` / `rememberLazyGridState` hoisted into the VM via `SavedStateHandle`, OR rely on `rememberSaveable` if Compose's default state restoration covers it — verify per screen).
- Right-pane tab selection (Podcast detail, Episode detail) — `rememberSaveable` keyed by `episodeId`.
- In-progress text in any input (in-library search, AskGemini composer) — `rememberSaveable`.

The reverse rotation (portrait → landscape) re-promotes the top of the back stack into the detail pane if and only if its route matches the master-detail's detail type (e.g., a `Route.EpisodeDetail` on top with the same `podcastId` as the current `Route.PodcastDetail` master). Otherwise the detail pane starts empty.

## 6. Per-screen rules (referencing the design mocks)

Each phase plan owns the precise mock alignment for its screen. The spec records only the cross-cutting rules.

- **Library** ("Library · 10" landscape" and the four-form-factor card): hero header "Your shelves" + sync subtitle, in-library search input, optional Pro "Folders" cluster (Morning / Long form / Indie / Saved for later) above the subscriptions grid/list. Subscriptions render as a horizontal grid of avatar+title cards with NEW badges. Detail pane (landscape) shows a flat preview of the selected show's recent episodes.
- **Search** ("Search · split master-detail"): tabs `Top / Shows / Episodes / People`, results column, detail column shows the focused podcast (Subscribe button, blurb, "Latest episodes" list).
- **Downloads** ("Downloads manager"): single column even in landscape; the right pane stays empty on this screen because Downloads has no selection-driven detail in the design. Header: "On device · 6 files · 108 MB · Wi-Fi only", "Remove played", capacity bar, sections "Active" (PROGRESS / QUEUED / PAUSED) and "Completed" (DONE).
- **Stats** ("Stats · Pour Over tier"): hero tier card, three KPI cards (TOTAL / EPISODES / TIME SKIPPED), DAILY STREAK card, "By show · this month" list with per-show duration bars. **No master-detail.**
- **Settings** ("Settings · playback panel"): when landscape, becomes master-detail — left pane is the settings index (Account / Listening / Schedule / Kofipod Pro / Privacy / About), right pane shows the selected panel. Portrait collapses to single column with nested nav. Mirrors the design's two-pane "Settings · playback panel" mock exactly.
- **Now Playing** ("Now Playing · full bleed"): full-bleed at all four sizes; rail hidden. Same composable the phone uses, but `size`-aware paddings (artwork is centered with a max width cap, secondary controls spread on the wider rows).
- **Podcast detail** ("Podcast detail + episode"): master = episode list (with All / Unplayed / Downloaded chips), detail = focused episode with the resume strip, metadata, and the Overview / Chapters / Mentioned / Discuss tabs.
- **Episode detail / AI** ("Episode detail · AI redesign (V3)"): full-bleed when navigated from non-master-detail contexts (8" portrait, 10" portrait); becomes the right pane of Podcast detail on the landscapes. Tab set unchanged from phone: Overview / Chapters / Summary / Mentioned · N / Discuss / Transcript. **No new tabs, no new content surfaces.**
- **AI insights** (Summary / Mentioned / Discuss / "Ask Gemini about this episode" composer-stub): tablet only widens the existing cards and tightens spacing per the V3 mock. The Discuss full-screen route (`AskGeminiScreen`) is unchanged on tablet except for max-width centering on 10".

## 7. Layout primitives to introduce (shared across phases)

These belong to Phase 1 and are reused by every later phase. **None of them are screens; they are layout containers.**

- `TabletSize` enum + `LocalTabletSize` `CompositionLocal`.
- `KofipodScaffold(size)` — wraps `Scaffold`, hosts rail + content + docked mini-player. Phone path uses the existing scaffold unchanged.
- `MasterDetailPane(master, detail, emptyDetail)` — two-column container, used when `size.isMasterDetail`.
- `RailDestination` data + `KofipodNavigationRail(size, destinations)`.
- `DockedMiniPlayer(size)` — adapts the existing mini-player.

## 8. Tokens, theming, dark mode

- Light only. Tokens stay in `ui/theme/`; the design opens with `KOFIPOD · ANDROID TABLET · HI-FI · LIGHT ONLY` and closes with "Dark mode skipped per scope — same tokens apply, swap `t={KP_DARK}` at the App level." No new tokens are introduced for tablet.
- Typography sizes are unchanged. Spacing helpers may introduce `size`-aware paddings, but only via the new layout containers — screen-level Composables don't branch on size.

## 9. Source-set constraints

- All new code in `commonMain`. Window size classification uses the multiplatform `material3.adaptive` window size API (already on classpath via Compose Multiplatform).
- Nothing Android-only leaks into `commonMain` (detekt enforces this — see project CLAUDE.md "Lint & static analysis").
- iOS compile (`compileKotlinIosSimulatorArm64`) must stay green for every phase.

## 10. Acceptance gates (apply to every phase)

A phase is done only when:

1. **Per-task gates fire** as per global CLAUDE.md (test audit, user exercise on a real tablet AVD, code review — each via sub-agent transcript).
2. **Paparazzi baselines** added or refreshed for the affected screens at all four canonical tablet dimensions (800×1200, 1200×800, 1000×1400, 1400×1000) AND the existing phone baselines remain byte-identical. A phone-baseline-changed diff is a regression unless explicitly approved.
3. **Emulator verification** on the Pixel Tablet AVD (`./gradlew :composeApp:installDebug && adb shell am start -n com.kofikodr.kofipod/.MainActivity`), then:
    - render the affected screen in **portrait**, verify against the matching portrait mock;
    - rotate to **landscape** (`adb shell settings put system accelerometer_rotation 0 && adb shell settings put system user_rotation 1`), verify against the matching landscape mock;
    - rotate back to portrait, verify master selection / scroll / input state survived;
    - then run on the Pixel_9a AVD to confirm phone has not regressed (phone stays portrait-locked, so no rotation step there).
4. **Lint clean** (`ktlintFormat`, `detekt`, `compileDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`, `:composeApp:testDebugUnitTest`, `:composeApp:verifyPaparazziDebug`).

## 11. Phases

Each phase has its own implementation plan under `docs/superpowers/plans/`. Phases land in order; later phases depend on Phase 1's primitives.

1. **Foundation & navigation** — `TabletSize`, `KofipodScaffold`, rail, docked mini-player, Stats promoted to rail. (Plan: `2026-05-11-tablet-phase-01-foundation.md`)
2. **Library** — shelves + folders + subscriptions, with landscape master-detail preview. (`-phase-02-library.md`)
3. **Search** — master-detail with podcast preview pane. (`-phase-03-search.md`) First-run starter packs in Search are **deferred** — see Phase 3 plan §3.5 deferral note.
4. **Downloads** — header, capacity, Active / Completed sections at tablet widths. (`-phase-04-downloads.md`)
5. **Stats** — Pour Over hero + KPIs + by-show list, single-column at all tablet sizes. (`-phase-05-stats.md`)
6. **Settings** — index + panel master-detail on landscape, nested on portrait, Daily check-in explainer mock. (`-phase-06-settings.md`)
7. **Now Playing** — full-bleed at all sizes, rail hidden. (`-phase-07-now-playing.md`)
8. **Podcast detail** — episode list + episode preview master-detail. (`-phase-08-podcast-detail.md`)
9. **Episode detail** — V3 redesign layout adaptation (Overview / Chapters / Transcript tabs). (`-phase-09-episode-detail.md`)
10. **AI insights** — Summary / Mentioned / Discuss panels and the "Ask Gemini" composer-stub at tablet widths. (`-phase-10-ai-insights.md`)

## 12. Non-goals (locked)

- No dark mode work.
- No new screens, routes, or VMs (Stats already exists; its rail entry is a routing change).
- No copy changes outside what the mocks visibly say.
- No phone visual diffs.
- No iOS-targeted tablet layout work (Android tablets only; iPad is not in scope, though iOS compile must stay green).
- No new dependencies beyond what's already on classpath (Material 3 adaptive window-size is already pulled in by Compose Multiplatform).
