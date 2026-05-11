# Phase 6 — Settings

> **STATUS: SKIPPED (2026-05-11).** Per user direction, the Settings screen reuses the existing phone layout on tablet for now. The two-pane master-detail mock and Daily scheduler tablet adaptation described below are deferred. Today's full-width list of settings rows already renders acceptably inside the rail-bounded content area at every tablet size — it just doesn't take advantage of the extra horizontal real estate the master-detail mock would offer. Revisit when the Settings index outgrows a single column or when the rest of the tablet redesign is in production. No `screens/settings` or `screens/scheduler` files are modified by this phase.

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §6 (Settings bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — sections "Settings · playback panel", "Daily scheduler explainer", and the four "Settings · {size}" mocks.
**Depends on:** Phase 1 (`MasterDetailPane`).
**Scope:** layout adaptation of `screens/settings` and the existing Daily check-in screen under `screens/scheduler`. **No new settings, no new toggles.** Every row already exists on phone.

## What the mocks show

- "Settings · playback panel" (10" landscape): two-pane.
  - Left pane (index): account header ("James M. · Drive synced 2m ago"), then sections **Account** (Profile, Google Drive, Sign out), **Listening** (Playback, Skip silence, Sleep timer, Download rules), **Schedule** (Daily check-in, Notifications), **Kofipod Pro** (Subscription, PKM connections, Bookmarks export), **Privacy** (Backups, Analytics, About).
  - Right pane: the selected panel's content — for Playback: Default speed (0.8× → 2.0× chips), Jump intervals (Back 30s / Forward 45s), toggles Skip silence / Boost dialogue / Headphone auto-resume / Ducking.
- "Daily scheduler explainer" (10" landscape): hero "One quiet visit per day", a card with Wake-up window, Constraints, Last actual run, "Run now (manual)" button, and a "A DAY IN THE LIFE" timeline. **All this already exists** as the current scheduler screen — tablet only re-lays-out.
- Tablet portraits and 8" landscape: single column index (no right pane); tapping a row navigates to the existing nested settings screens.

## Tasks

### 6.1 Extract `SettingsContent(state, onEvent, size)`
- Refactor `SettingsScreen` (the index) into a stateless content composable. Same for each nested panel screen.

### 6.2 Tablet portraits + 8" landscape: single column
- Just the index, full-width. Tap → navigate to the existing nested screen (unchanged routing).
- **Tests:** Paparazzi at 800×1200, 1000×1400.

### 6.3 10" landscape: master-detail
- New `SettingsContentTabletMasterDetail`:
  - Master (left, 320 dp fixed): account header + index list.
  - Detail (right, fills remainder): the selected panel rendered inline. Composable resolution: a `when (selectedPanel)` map keyed by `SettingsPanelId` enum, each branch invokes the existing nested panel composable extracted via the same pattern as Phase 2's split.
- Selection state in `SettingsViewModel` as `selectedPanel: StateFlow<SettingsPanelId>` with a default of `Playback` to mirror the mock.
- Back navigation from a deep link or rail re-entry restores the last-selected panel.
- **Tests:** Paparazzi for Playback, Daily check-in, and Privacy → Backups panels at 1400×1000.

### 6.4 8" landscape master-detail (smaller variant)
- Same as 10" landscape but with master at 260 dp. Verify against the design's "Settings · 8" landscape" mock — which shows the master-detail pattern.

### 6.5 Daily check-in panel
- The "Daily scheduler explainer" mock is the right-pane content when **Daily check-in** is selected. Refactor the existing scheduler screen into a stateless `DailyCheckInPanel` composable; reuse it both as a standalone destination (phone + tablet portraits + 8" landscape) and as the right pane on 10" landscape.

## Acceptance

- Four tablet Paparazzi baselines for Settings; additional baselines for Playback panel and Daily check-in panel on 10" landscape.
- Phone Paparazzi unchanged.
- Pixel Tablet AVD rotation flow: open Settings on 10" portrait — single column index. Rotate to landscape — index becomes the master pane, last-selected panel (default `Playback`) is auto-shown in the right pane. Rotate back to portrait — single column index returns, the previously-shown panel becomes a forward nav entry (back returns to the index). Any in-progress input on a panel (e.g., sleep timer duration field) must survive both rotations.
- Lint / type / iOS / paparazzi green.

## Out of scope

- Any new setting, toggle, slider, or copy change.
- Any change to the actual WorkManager scheduler behavior.
- Notifications panel content changes.
