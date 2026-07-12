# Implicit "mark podcast seen" — design

**Date:** 2026-07-12
**Status:** Approved (brainstorming) — pending implementation plan
**Scope:** Single feature, `:composeApp`. Phone + tablet.

## Problem

The library "new" indicator (`NewDot`) is currently a purely computed signal with no
stored state. A podcast (and the list/group containing it) shows the dot when it has
≥1 episode that was published after the user subscribed **and** has never been played:

```sql
-- Episode.sq :: selectNewEpisodeCountsByPodcast (current)
SELECT e.podcastId AS podcastId, COUNT(*) AS newCount
FROM Episode e
INNER JOIN Podcast p ON p.id = e.podcastId
LEFT JOIN PlaybackState ps ON ps.episodeId = e.id
WHERE ps.episodeId IS NULL
  AND e.publishedAt > p.addedAt
GROUP BY e.podcastId;
```

The **only** way to clear the dot today is to start (or "mark played") every new
episode — both create a `PlaybackState` row, which drops the episode out of the query.
There is no way to say "I've looked at this show, dismiss the dot" without touching each
episode. The app also has no reversible dismiss path of any kind.

## Goal

Add an **implicit** "mark seen" so that opening a podcast's episode list and dwelling on
it briefly dismisses that podcast's new indicator — without marking anything played, and
without disturbing the existing play-to-dismiss behavior.

### Non-goals (v1)

- No explicit gesture (long-press "mark as seen", list-level "mark all seen"). The chosen
  storage supports adding one later for free, but it is out of scope now.
- No change to how episodes themselves are rendered as new/played inside a list.
- No "unseen" / un-dismiss UI.

## Behavior

- Opening `PodcastDetailScreen` for a podcast and remaining on it for **~1.5s** marks that
  podcast **seen**. Its `NewDot` clears, and its contribution to the parent list's
  aggregate dot drops.
- **Mis-tap + immediate back-out does NOT clear.** The dwell timer runs inside a
  `LaunchedEffect` that is cancelled when the screen leaves composition, so leaving before
  1.5s skips the write. No extra state is needed to achieve this.
- **Play / mark-played still clears individual episodes**, unchanged. Seeing and playing
  are independent dismiss channels.
- A newly published episode (published *after* the last visit) re-lights the dot. This is
  the intended "new" behavior, preserved.

## Design

### Storage — per-podcast watermark

Add one nullable column to `Podcast`:

```
lastSeenAt INTEGER   -- epoch millis; NULL = never opened since subscribing
```

Chosen over a per-episode `seenAt` column/table because, given the dwell trigger (not a
per-episode-visibility trigger), per-episode buys no precision — it would write N rows per
visit instead of 1, on the very scroll path that CLAUDE.md documents as perf-tuned
("the detail screen's episode list was tuned for scroll-during-playback"). The watermark
is a single write per visit and makes a future explicit "mark all seen" gesture trivial
(bump / clear `lastSeenAt`).

### Query change (the crux)

`selectNewEpisodeCountsByPodcast` gains one clause:

```sql
WHERE ps.episodeId IS NULL
  AND e.publishedAt > MAX(p.addedAt, COALESCE(p.lastSeenAt, 0))
```

`MAX(a, b)` is SQLite's two-argument scalar (larger of the two); `COALESCE` treats a null
watermark as 0 so behavior is identical to today until a podcast is first seen.
"new" now means: *never played* **AND** *published after the later of {subscribed,
last opened}*.

### New write

`Podcast.sq`, mirroring the existing `setLastChecked` setter:

```sql
setLastSeen:
UPDATE Podcast SET lastSeenAt = ? WHERE id = ?;
```

Exposed on `LibraryRepository` (which already owns Podcast library mutations such as
`setAutoDownload` / `moveToList`):

```kotlin
fun markSeen(podcastId: String, seenAt: Long)   // runs setLastSeen
```

### Trigger wiring

`PodcastDetailViewModel` **already injects `library: LibraryRepository`** (constructor
param 3), so no new constructor parameter and no Koin factory change are required. Add:

```kotlin
fun markSeen() {
    val id = state.value.summary?.id ?: podcastId
    library.markSeen(id, Clock.System.now().toEpochMilliseconds())
}
```

`PodcastDetailScreen` fires it after a dwell:

```kotlin
LaunchedEffect(podcastId) {
    delay(SEEN_DWELL_MS)   // 1500L
    viewModel.markSeen()
}
```

- `LaunchedEffect` cancellation on screen exit *is* the dwell guard — no separate
  visibility/back-press bookkeeping.
- `markSeen()` is idempotent (sets `lastSeenAt = now`), so a config-change re-fire (e.g.
  rotation after 1.5s) is harmless.
- Timestamp uses `Clock.System.now().toEpochMilliseconds()` per the KMP convention
  (no `System.currentTimeMillis()` in `commonMain`).

### Surfaces — no per-surface work

`EpisodesRepository.newEpisodeCountsFlow()` is a reactive SQLDelight `Flow`. When
`lastSeenAt` updates, the query re-emits and every existing dot surface updates
automatically and consistently:

- `LibraryScreen` `ListTile` / `UnfiledTile` (via `LibraryViewModel.groupsWithNew`)
- `LibraryScreenTablet` `ListTile`
- `LibraryDetailScreen` `PodcastCard` (via `LibraryDetailViewModel.podcastsWithNew`)

The list-level dot clears only once *all* its podcasts are seen — this falls out of the
existing `groupsWithNew` aggregation with no change.

### Migration & backup

- New migration `22.sqm` (moves schema 22 → 23):
  `ALTER TABLE Podcast ADD COLUMN lastSeenAt INTEGER;` — additive and nullable, so safe;
  existing rows get `NULL` and behave exactly as before.
- Bump `DB_SCHEMA_VERSION` 22 → 23 in `backup/Manifest.kt` in lockstep (the
  `ManifestTest.dbSchemaVersion_matchesGeneratedSchema` drift guard requires it).
- `.kpbak` restore stays compatible: an older backup (schema 22) restored into the new
  build migrates cleanly via `22.sqm`; the column is additive.
- Update the stale "current schema version is 21" note in `CLAUDE.md` to 23 (the repo is
  already at 22 before this change).

## Testing

- **Query behavior test** (highest value; the query is the heart of the feature). Using the
  in-memory SQLDelight JVM driver — the same harness other JVM unit tests use — assert:
  - an episode with `publishedAt` **before** `lastSeenAt` is **not** counted in `newCount`;
  - an episode with `publishedAt` **after** `lastSeenAt` **is** counted;
  - inserting a `PlaybackState` row still removes an episode from `newCount` independently
    of the watermark (both dismiss channels work);
  - a `NULL` `lastSeenAt` reproduces the pre-change behavior (`publishedAt > addedAt`).
- `ManifestTest` passes once `DB_SCHEMA_VERSION` is bumped in lockstep.

Note: the project's stated test scope is Compose UI + Paparazzi, but JVM unit tests already
exist (`ManifestTest`, `AiSummaryJsonTest`, `DiscussWireTest`) and the query is the risky
core, so a focused JVM query test is warranted here.

## Accepted edge case

If an episode is published in the small window between the episode list being fetched and
the 1.5s dwell firing, `markSeen(now)` will mark it seen even though it never appeared in
the list the user looked at, and it will not show as new afterward. This is bounded by sync
cadence (rare) and low-impact (one missed dot). Avoiding it would require tracking the max
`publishedAt` of episodes actually shown; not worth the complexity for v1. Using `now` is
the deliberate call.

## Files touched (anticipated)

- `composeApp/src/commonMain/sqldelight/.../db/Podcast.sq` — `lastSeenAt` column + `setLastSeen`
- `composeApp/src/commonMain/sqldelight/.../db/Episode.sq` — `selectNewEpisodeCountsByPodcast` WHERE clause
- `composeApp/src/commonMain/sqldelight/.../db/migrations/22.sqm` — new migration
- `composeApp/src/commonMain/kotlin/.../data/repo/LibraryRepository.kt` — `markSeen(...)`
- `composeApp/src/commonMain/kotlin/.../ui/screens/detail/PodcastDetailViewModel.kt` — `markSeen()`
- `composeApp/src/commonMain/kotlin/.../ui/screens/detail/PodcastDetailScreen.kt` — dwell `LaunchedEffect`
- `composeApp/src/commonMain/kotlin/.../backup/Manifest.kt` — `DB_SCHEMA_VERSION` 22 → 23
- `CLAUDE.md` — schema-version note 21 → 23
- New JVM unit test for the query behavior
