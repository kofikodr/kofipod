# Phase 7 — Now Playing

**Spec:** `docs/superpowers/specs/2026-05-11-kofipod-tablet-design.md` §3 (rail hidden on Player), §6 (Now Playing bullet)
**Design (authoritative):** `docs/kofipod-tablet-design.html` — sections "Player · all four form factors" and "Now Playing · full bleed", plus the four "Now Playing · {size}" mocks.
**Depends on:** Phase 1 (rail-hidden rule on `Route.Player`).
**Scope:** layout adaptation of `screens/player` only. **No new playback features, no new controls.** Every element in the mocks exists today.

## What the mocks show

- Full-bleed dark-tinted top area, "Now Playing · From queue" eyebrow.
- Show name in caps ("SIGNAL · NOISE"), episode badge "EP 214 · 13:48 · CHAPTER 3".
- Large two-line title ("Vim, Emacs, and the first taste of extensibility").
- Subtitle "Signal & Noise · with Alex Eaves".
- Big artwork block centered.
- Scrubber with current/end timestamps ("18:42" / "37:26"), speed chip "1.4×".
- Secondary action row: Snip, Bookmark, Chapters, Transcript, Queue · 4.
- Same layout at all 4 sizes — only paddings and artwork max-width change.

## Tasks

### 7.1 Extract `PlayerContent(state, onEvent, size)`
- Standard refactor. Phone branch unchanged.

### 7.2 Tablet `size`-aware paddings
- Single tablet layout, parameterized by `size`:
  - Artwork max width: 360 dp (8" portrait / landscape), 480 dp (10" portrait), 560 dp (10" landscape).
  - Horizontal padding for the title block: 32 dp (8") / 64 dp (10").
  - Secondary action row icon size: 28 dp (8") / 32 dp (10"); label visible on 10" landscape only (matches mock).
- Layout stays single column at every tablet size — confirmed by the four mocks all using the same vertical stack.
- **Tests:** Paparazzi at all 4 tablet sizes.

### 7.3 Rail-hidden rule
- Already wired in Phase 1 (`KofipodScaffold` hides the rail when `currentRoute == Route.Player`). This phase verifies via an integration test.

### 7.4 Docked mini-player hidden on Player
- Already wired in Phase 1. Verified by Paparazzi (no mini-player visible in the Player snapshots).

## Acceptance

- Four tablet Paparazzi baselines.
- Phone Paparazzi unchanged.
- Pixel Tablet AVD rotation flow: open Player, verify full-bleed (rail hidden, mini-player hidden). Rotate portrait ↔ landscape — artwork max-width and side paddings reflow per `size`, scrubber position and playback state survive. Back from Player → rail returns and mini-player is visible in the previous screen.
- Lint / type / iOS / paparazzi green.

## Out of scope

- Scrubber gesture changes.
- Chapter / Bookmark / Snip behavior changes.
- Queue sheet redesign — `Queue · 4` still routes to the existing queue surface.
