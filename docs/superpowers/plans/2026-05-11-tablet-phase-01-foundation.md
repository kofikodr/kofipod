# Phase 1 — Foundation & navigation

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §2, §3, §4, §7
**Design (authoritative):** `docs/kofipod-tablet-design.html` — opening "One responsive UI across four form factors" card; rail and docked mini-player visible in every tablet screen mock.
**Scope:** layout primitives only — `TabletSize`, scaffold, rail, docked mini-player, Stats promoted to rail. No screen content changes.
**Skills:** `superpowers:writing-plans` (this doc), `superpowers:test-driven-development` (rail / scaffold tests first), `superpowers:verification-before-completion`, `superpowers:requesting-code-review` after each task.

## Constraints

- Phone build must be **byte-identical** in behavior. The phone code path (`KofipodBottomBar`) is preserved; selection between phone and tablet happens at one site (`AppShell`).
- `commonMain` only. No `androidx.*` non-KMP imports. Use `androidx.compose.material3.adaptive.windowsizeclass` (already pulled in transitively by Compose Multiplatform; verify before coding — if not, fall back to `BoxWithConstraints` at the single classifier site, NOT per-screen).
- iOS compile must stay green for every commit.

## Tasks (each task = full mandatory workflow per global CLAUDE.md)

### 1.0 Tablet AVD provisioning (one-time, host setup)

**Already done as part of this plan's authoring** — recorded here so re-clones can reproduce.

- AVD name: `Pixel_Tablet`
- Device profile: `pixel_tablet` (Google) — 1600×2560 px @ 276 ppi → ~840 × 1340 dp portrait, ~1340 × 840 dp landscape, which lands in the spec's **10" portrait / 10" landscape** breakpoints.
- System image: `system-images;android-36;google_apis_playstore;arm64-v8a` (already installed on this host; matches the rest of the team's standard image).

Creation command (idempotent — `--force` overwrites):

```bash
SDK=~/Library/Android/sdk
echo "no" | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
  --name "Pixel_Tablet" \
  --package "system-images;android-36;google_apis_playstore;arm64-v8a" \
  --device "pixel_tablet" \
  --force
```

Launch:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_Tablet -netdelay none -netspeed full &
```

Rotation control during verification (used by every later phase):

```bash
adb shell settings put system accelerometer_rotation 0    # disable auto-rotate
adb shell settings put system user_rotation 0             # 0=portrait, 1=landscape (90° CCW), 3=landscape (90° CW)
```

**8" tablet coverage:** the spec's 8" portrait (800×1200) and 8" landscape (1200×800) breakpoints are exercised via **Paparazzi snapshots** at those exact dimensions, not via a second AVD. If interactive 8" verification is needed in a later phase (e.g., to debug a master-detail bug only reproducible at the smaller width), create a `Medium_Tablet` AVD with `--device medium_tablet` using the same image — but **do not block phase completion on it**; Paparazzi at 8" sizes plus Pixel_Tablet interactive verification at 10" is the standing acceptance bar.


### 1.1 `TabletSize` classifier
- New file `ui/layout/TabletSize.kt` in `commonMain`. Enum: `Tablet8Port`, `Tablet8Land`, `Tablet10Port`, `Tablet10Land`. Plus an `isMasterDetail: Boolean` and a `railMode: RailMode` (`IconOnly | IconLabel | Expanded`).
- `rememberTabletSize(): TabletSize?` reads window size class; returns `null` for phone (< 600 dp width).
- `LocalTabletSize` `CompositionLocal` (default `null`).
- **Tests:** unit test the size→enum mapping at the 4 canonical dp pairs from the spec.

### 1.2 `KofipodScaffold`
- New file `ui/shell/KofipodScaffold.kt`. Wraps `Scaffold`, slot-based: `content`, optional `topBar`.
- When `LocalTabletSize.current != null`: lays out `Row(rail, Column(content, dockedMiniPlayer))`; rail hidden if `currentRoute == Route.Player`.
- When `null`: delegates to current phone scaffold (move the existing `Scaffold` body out of `AppShell.kt` into this composable; phone path unchanged).
- **Tests:** Paparazzi at `1400×1000` showing rail + content placeholder + docked mini-player; phone baseline at `412×892` unchanged.

### 1.3 `KofipodNavigationRail`
- New file `ui/shell/KofipodNavigationRail.kt`.
- Destinations list pulled from a single `TABS_TABLET` constant: `Library, Search, Downloads, Stats, Settings` (in that order per spec §3).
- Mode-aware (icon-only / icon+label / expanded). Expanded mode renders the **brand block** (Kofipod logo + wordmark) at top and the **profile chip** ("JM" avatar + "James M." + "● Drive" sync status) at bottom — both read existing state from `AccountRepository` / Drive sync state via Koin. No new repositories.
- Selection: pop-to-start, `launchSingleTop = true`, identical to phone bottom bar.
- **Tests:** Paparazzi snapshots at each of the 3 rail modes; tap a destination via `composeRule` and assert nav callback was invoked with the right `Route`.

### 1.4 `DockedMiniPlayer`
- Extract the existing phone mini-player into a `size`-aware composable. Phone branch: unchanged. Tablet branch: full-content-width strip (rail keeps its own background), 72 dp tall, layout per the "Now playing" strip in every tablet mock.
- Hidden when `currentRoute == Route.Player`, no active episode, or `Route.Onboarding`.
- **Tests:** Paparazzi at all 4 tablet sizes with a stub active episode; phone baseline unchanged.

### 1.5 Promote Stats to top-level rail entry
- Add `Stats` to `TABS_TABLET` (already in `Routes.kt` — verify it exists; if it doesn't, it's a routing-only addition referencing the existing `screens/stats` content).
- Add the icon to `KPIconName` if missing — reuse the existing stats icon already in the screen.
- **Phone bottom bar:** NOT touched. Stats remains a deep-link on phone (per spec §3 footnote that the design promotes Stats only on tablet).
- **Tests:** unit test the destinations list; integration test that tapping Stats on tablet navigates to `Route.Stats`.

### 1.6 Wire `AppShell` to delegate
- `AppShell.kt`: at the top of the composable, read `rememberTabletSize()`; provide via `LocalTabletSize`; pick `KofipodScaffold` either way (phone path = same as today). Move the existing `Scaffold` body wholesale into `KofipodScaffold`'s phone branch — no behavior change.
- Hide rail + show full-bleed Player when `currentRoute == Route.Player` (existing rule, ported).

## Acceptance (this phase)

- All four canonical Paparazzi sizes (800×1200, 1200×800, 1000×1400, 1400×1000) render the rail in the correct mode with a stub content area.
- Pixel Tablet AVD: launching the app shows the rail; tapping each rail item navigates correctly; **rotating between portrait and landscape changes the rail mode** (e.g., 10" portrait `IconLabel` ↔ 10" landscape `Expanded`) **without losing nav state** — currently-selected tab stays selected, no recompose flash. Verify on both 10" and 8" emulator profiles.
- Pixel_9a AVD: phone build is visually unchanged — bottom bar present, Stats still deep-linkable but NOT in the bar.
- `./gradlew :composeApp:ktlintFormat :composeApp:detekt :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testDebugUnitTest :composeApp:verifyPaparazziDebug` — all green.

## Risks

- `material3.adaptive.windowsizeclass` may not be on the KMP classpath. If `./gradlew :composeApp:compileKotlinIosSimulatorArm64` fails on the import, fall back to a single `BoxWithConstraints` at the `rememberTabletSize` site — do NOT scatter `BoxWithConstraints` across screens.
- Moving the existing scaffold body into `KofipodScaffold` is the highest regression risk to the phone build. Diff against the current `AppShell.kt` carefully; the phone branch must be a byte-for-byte move.
