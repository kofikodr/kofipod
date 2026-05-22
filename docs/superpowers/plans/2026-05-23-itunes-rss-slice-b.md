# Slice B — RSS feed fallback for iTunes-only shows + PI crawl lag

**Builds on:** the iTunes Search work (Slice A, currently uncommitted on master) — `data/api/ItunesSearchApi`, `data/repo/AggregateSearchSource`, `SourceId.{PodcastIndex, ITunes}`, `SearchViewModel.requestNavigation` PI-hydrate.

**Problem.** Today the app reaches episodes through Podcast Index's REST JSON. PI is an aggregator that crawls publisher RSS feeds on its own cadence. This leaves two holes:

1. **iTunes-only shows are a dead-end.** A tap on an iTunes search result PI doesn't index calls `podcastByFeedUrl` → no-match → toast "feed isn't in our index yet" → user bounces back. The card was tappable but useless.
2. **PI crawl lag.** Even for shows PI does index, new episodes don't appear until PI re-crawls — minutes for major shows, hours-to-days for niche ones. The daily notification worker inherits the same lag.

**Solution.** Add a direct RSS fetch + parse path. Publisher RSS is the source of truth for episodes; PI is a cache with crawl lag. We route around PI exactly where lag matters:

| Path | Source | Why |
| --- | --- | --- |
| Search results | PI + iTunes | Already shipped (Slice A). |
| Detail-screen open (PI-known show) | PI + RSS merged by GUID | Picks up just-dropped episodes the user opened the screen to see. |
| Detail-screen open (iTunes-only show) | RSS only | PI doesn't know it; publisher RSS is the only source. |
| Daily worker — notify-on shows | RSS direct | User asked to be told; spend the request. |
| Daily worker — notify-off PI shows | PI as today | No bandwidth burn for shows the user hasn't opted into. |
| Daily worker — notify-off iTunes-only shows | skip | No notify request, no RSS-known refresh trigger; refreshes on next detail-open. |

**Storage.** We do **not** persist raw XML. We parse → discard the body → write `Episode` rows into the existing table (same as today's PI JSON path). A tiny `RssFeedCache` table holds per-feed HTTP cache headers (`etag`, `lastModified`, `lastFetchedAt`) so the next fetch can send `If-None-Match` / `If-Modified-Since` and get a ~200-byte **304 Not Modified** when nothing has changed.

**Skills:** `superpowers:writing-plans` (this doc), `superpowers:test-driven-development` (parser + merger + cache), `superpowers:verification-before-completion`, `superpowers:requesting-code-review` after each slice.

---

## Cross-slice constraints

- **GPL-3.0-or-later** SPDX header on every new source file.
- **KMP-safe** — every new dep must have an iOS klib. Verify on `compileKotlinIosSimulatorArm64` before merge.
- **detekt forbidden imports** — no `java.*`/`javax.*`/`kotlin.jvm.*` in `commonMain`.
- **No `Ktor Logging` plugin** on the shared `HttpClient` — RSS URLs don't carry secrets, but the same client serves PI API calls which do.
- **All 10 workflow steps per slice**: test audit → run tests → emulator exercise → kode-review.
- **Per-slice DB migration** is one new `.sqm` and one bump of `DB_SCHEMA_VERSION` in `backup/Manifest.kt`. `ManifestTest.dbSchemaVersion_matchesGeneratedSchema` guards drift.

---

## Slice B.1 — XML parser + RSS data types

**Goal.** Pure-function XML → typed Kotlin. No HTTP, no DB, no UI. Smallest unit so the parser can be tested against real fixtures in isolation.

**Files**

- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/rss/RssChannel.kt` — channel + episode data classes (`RssChannel`, `RssEpisode`, `RssEnclosure`).
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/rss/RssParser.kt` — `object RssParser { fun parse(body: String): RssChannel }`. Pure.
- `gradle/libs.versions.toml` + `composeApp/build.gradle.kts` — add `ksoup` (Apache-2.0, GPL-compatible, KMP klib for iOS) or `xmlutil` (Apache-2.0, KMP). License check explicitly recorded in the commit message.

**Fields to extract per episode**

- `guid` — `<guid>` value, else `<enclosure url>` (durable ID; falls back to enclosure URL since some feeds omit `<guid>`).
- `title`, `description` (CDATA-aware), `pubDate` (RFC 2822 → `Instant`), `link`.
- `enclosure.url`, `enclosure.type`, `enclosure.length` (audio file pointer — required; episodes without enclosure are dropped).
- `<itunes:duration>` (HH:MM:SS or seconds), `<itunes:image>`, `<itunes:episode>`, `<itunes:season>`, `<itunes:explicit>`.
- Channel-level: `title`, `description`, `link`, `<itunes:author>`, `<itunes:image>`, `<itunes:category>`.

**Tests** (`commonTest/data/rss/RssParserTest.kt`)

Real fixtures saved under `composeApp/src/commonTest/resources/rss/`. Each fixture is a real publisher feed snapshot, trimmed to ≤ 5 episodes to keep the binary footprint small. Cover:

- Simplecast (NYT The Daily) — modern, well-formed.
- Megaphone — modern, ad-tracking enclosures.
- Libsyn — older shows, GUIDs from a custom domain.
- Substack — markdown-in-CDATA descriptions.
- Spotify-for-Podcasters — minimal RSS shape.
- Malformed: missing `<guid>` → falls back to enclosure URL.
- Malformed: bad `pubDate` → episode kept, `pubDate = null`, not dropped.
- Missing enclosure → episode dropped.
- iTunes namespace absent → still parses, fields default.
- HTML entities in title — decoded.

**Exit criteria.** Detekt clean, ktlint clean, `compileKotlinIosSimulatorArm64` green, all parser tests green, kode-review 0 critical / 0 high.

---

## Slice B.2 — RSS fetch client + HTTP cache table

**Goal.** Wrap the parser in a Ktor-based fetcher that honors ETag / Last-Modified, stores cache headers in a new table, and returns a discriminated result.

**Files**

- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/rss/RssFeedClient.kt` — Ktor wrapper:
  - `suspend fun fetch(feedUrl: String): RssFetchResult` returning a sealed type:
    - `RssFetchResult.Fresh(channel: RssChannel)` — 200 + parsed.
    - `RssFetchResult.NotModified` — 304, caller should use whatever's already in the Episode table.
    - `RssFetchResult.Unauthorized` — 401/403, surfaces "feed is paywalled".
    - `RssFetchResult.NetworkError(cause)` — anything else; caller decides UX.
  - Reads cache row → sends `If-None-Match`, `If-Modified-Since` → on 200, writes back new `etag`/`lastModified`/`lastFetchedAt`.
  - Follows redirects (Ktor default) — clamp to a small max-redirect (~5) to avoid loops.
  - Per-request timeout via `withTimeout(15_000L)` — same shape as `AggregateSearchSource`'s 10s per-source budget but RSS bodies can be larger.
- `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/RssFeedCache.sq` — new table:
  - `feedUrl TEXT PRIMARY KEY NOT NULL`
  - `etag TEXT` (nullable, host may not support)
  - `lastModified TEXT` (nullable, RFC-1123 HTTP date string — store raw, don't parse)
  - `lastFetchedAt INTEGER NOT NULL`
  - Queries: `upsertCache`, `selectByFeedUrl`, `deleteByFeedUrl`.
- `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/migrations/N.sqm` — `CREATE TABLE RssFeedCache (...)`. Bump `N` to the next free number.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/backup/Manifest.kt` — bump `DB_SCHEMA_VERSION` in lockstep with the migration.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/di/CommonModule.kt` — bind `single { RssFeedClient(client = get(), db = get()) }`.

**Tests** (`androidUnitTest/data/rss/RssFeedClientTest.kt`)

`MockEngine`-based. Cover:

- First fetch: no cache row → server returns 200 + ETag → result is `Fresh`, cache row written with returned ETag.
- Second fetch: cache row exists → request carries `If-None-Match` → server 304 → result is `NotModified`, cache row's `lastFetchedAt` updated.
- 403 → `Unauthorized`, no cache write.
- Network failure → `NetworkError`, no cache write.
- Host that returns `Last-Modified` but not `ETag` → both fields handled, conditional GET still works.
- Redirect chain (`op3.dev` → `feeds.simplecast.com`) — follows correctly.

Plus `ManifestTest.dbSchemaVersion_matchesGeneratedSchema` must still pass (drift guard).

**Exit criteria.** All Slice B.1 gates + migration applies cleanly on a fresh DB and on an upgraded DB (uninstall + reinstall on emulator to verify fresh path; install over an existing build to verify migration path).

---

## Slice B.3 — RSS merge on Podcast Detail (close PI crawl lag)

**Goal.** For shows PI knows, fetch RSS in parallel with PI's episodes call on detail-screen open, merge by GUID, append RSS-only items tagged "new from feed". Lowest-risk, highest-immediate-value slice — every existing user feels it instantly.

**Files**

- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/EpisodeMerger.kt` — pure object:
  - `fun merge(piEpisodes: List<Episode>, rssEpisodes: List<RssEpisode>): MergedEpisodes`
  - **Identity stays PI** for episodes both sources have — `Episode.id` is preserved so downloads, playback state, AI summaries, transcripts, AudioUploadCache, DiscussMessage FKs stay intact.
  - **Display + audio fields prefer RSS** — title, description, pubDate, duration, link, artwork, audioUrl (enclosure URL). Reason: publisher is the source of truth, corrections propagate, ad-stitching feeds (Megaphone, Spotify-for-Podcasters) rotate enclosure URLs and we want fresh ones for new downloads. Existing local downloads aren't affected (saved file path stored separately).
  - **Blank/null RSS field = no change**, never overwrite a populated PI field with an empty RSS value (defense against publisher transient errors or parser hiccups dropping a field).
  - **No-op when equal** — only persist if at least one field actually differs. Avoids churn on every refresh.
  - **PI-only episodes are NOT deleted** when RSS doesn't contain them. RSS feeds are typically paginated to recent N items; older episodes naturally fall off the publisher's feed even though PI keeps full history. PI-known episodes survive RSS pagination and intentional pulls.
  - Match key: GUID first, enclosure URL fallback (with same canonicalization as `FeedUrlCanonicalizer` — strip query params, lowercase host).
  - RSS-only episodes → tagged `EpisodeOrigin.RssNew`, sorted by pubDate, appended above the merged list (newest first matches existing detail-screen ordering).
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/domain/Episode.kt` — extend with `origin: EpisodeOrigin` field (default `PodcastIndex`). Sealed enum: `PodcastIndex`, `RssNew`, `Rss` (the latter for iTunes-only Slice B.4).
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/EpisodesRepository.kt` — modify `refresh()` to fan out PI + RSS in parallel via `coroutineScope`/`async`, then `EpisodeMerger.merge(...)`. **Do not write RSS-only rows to the DB until the user interacts** (play / download / queue / open) — keeps the Episode table from churning on feed-side edits/deletes. In-memory only via a `StateFlow<MergedEpisodes>` returned alongside the existing DB-backed flow.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailScreen.kt` — render an `EpisodeOrigin.RssNew` chip on rows. Subtle: same chip style as the Slice A source tag, label "new".
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/detail/PodcastDetailViewModel.kt` — wire the merged flow into `DetailUiState`. Care: per CLAUDE.md performance invariants, keep `playingEpisodeId` and `activePlayback` as separate StateFlows; don't fold RSS-merge state into a high-tick flow.
- **Interaction → persist.** Tapping play/download/queue on an `EpisodeOrigin.RssNew` row writes a real `Episode` row (origin recorded as `RssNew` → upgraded silently to `PodcastIndex` on PI's next crawl when GUIDs match).

**Tests**

- `commonTest/data/repo/EpisodeMergerTest.kt` — GUID match, enclosure-URL fallback match, dedup ordering, RSS-only append, pubDate sort. Plus the field-precedence contract: PI identity preserved on match, RSS display fields overwrite PI, blank RSS field leaves PI value intact, equal values produce no-op (no diff). PI-only episodes survive when RSS pagination drops them. Pure function, no Koin / DB.
- `androidUnitTest/data/repo/EpisodesRepositoryTest.kt` — refresh with stub `PodcastIndexApi` + stub `RssFeedClient`. Verify PI episodes are persisted, RSS-only are not (until interaction).
- Paparazzi: `PodcastDetailScreenSnapshots` updated to include a "new from feed" row.

**Emulator exercise**

- Pick a podcast known to lag PI (any niche show). Note the latest PI episode date. Confirm a publisher feed has a newer episode (check the feed URL in a browser). Open the detail screen. RSS-only newer episode should appear with the "new" tag. Tap play → episode persists into the DB.

**Exit criteria.** All gates. No `Episode` table churn from RSS edits (verify by re-fetching with a synthetic RSS that mutates titles on episodes the user hasn't tapped — DB row unchanged).

---

## Slice B.4 — iTunes-only shows route through RSS (close the dead-end)

**Goal.** A tap on an iTunes search result that PI doesn't know lands on a working detail screen instead of a toast. The show is persisted into the DB so subscribe / download / playback state all work.

**Files**

- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/RssPodcastBootstrap.kt` — new helper:
  - `suspend fun bootstrap(feedUrl: String, fallbackSummary: PodcastSummary): Podcast` — fetches RSS, derives a Podcast row from channel-level fields, falls back to iTunes summary fields where RSS is sparse (e.g., category). Generates `Podcast.id = "rss:" + sha256(canonicalFeedUrl)` (deterministic, doesn't collide with PI's numeric ids or iTunes' `itunes:` prefix).
  - Writes the Podcast row + RSS-derived Episode rows to the DB in a single transaction.
  - Idempotent: if a `rss:<hash>` Podcast already exists, returns the existing row and just refreshes episodes.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/search/SearchViewModel.kt` — modify `requestNavigation`:
  - PI hydrate path unchanged for happy case.
  - On `podcastByFeedUrl` no-match: instead of `HydrationFailed`, call `RssPodcastBootstrap.bootstrap(...)` → on success, navigate to `Route.PodcastDetail(rssId)` → on failure, current toast as final fallback.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/EpisodesRepository.kt` — branch on `Podcast.id` prefix:
  - Numeric → PI + RSS merge (Slice B.3 path).
  - `rss:` → RSS only (no PI call at all — there's no feedId to pass).
  - `itunes:` → unreachable here after hydration; keep as defensive error.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/repo/PodcastsRepository.kt` — `subscribe`/`unsubscribe` already key on `Podcast.id` as TEXT, so `rss:...` ids flow through with no schema change.
- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/domain/PodcastSummary.kt` — extend `sources` semantics: a `rss:` Podcast carries `sources = setOf(SourceId.ITunes)` (it was discovered via iTunes but content comes from RSS). Optional: add `SourceId.RssDirect` if we want to distinguish in UI.

**Tests**

- `androidUnitTest/data/repo/RssPodcastBootstrapTest.kt` — happy path, idempotency, RSS-fetch failure, paywalled feed.
- `androidUnitTest/ui/screens/search/SearchViewModelTest.kt` — extend to cover the new fallback: PI no-match + RSS success → `NavigateToPodcast("rss:...")`; PI no-match + RSS failure → `HydrationFailed`.
- Manual emulator: pick an iTunes-only show. Tap → detail screen opens with episodes.

**Exit criteria.** All gates. SAF backup `.kpbak` of a DB containing `rss:...` Podcasts restores cleanly (no FK violations, no missing rows).

---

## Slice B.5 — EpisodeCheckWorker uses RSS for notify-on shows

**Goal.** Daily notifications fire on the publisher's actual schedule, not PI's crawl schedule, for shows the user has explicitly subscribed to push for.

**Files**

- `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/background/EpisodeCheckWorker.kt` — branch per podcast:
  - `notifyEnabled && podcast.id starts with "rss:"` → RSS only.
  - `notifyEnabled && numeric PI id` → RSS direct (skip PI to defeat lag).
  - `!notifyEnabled && numeric PI id` → PI as today (no extra bandwidth, no behavior change).
  - `!notifyEnabled && rss:` → skip (no notify request, content refreshes on next detail-open in B.3/B.4).
- RSS path uses the same `RssFeedClient` with ETag/IMS — most days these are 304s, ~200 bytes each.
- New-episode detection: diff GUIDs against the `Episode` table; new GUIDs trigger a notification and a real DB row write (so the next detail-open shows them as PI-known, not "new from feed", once PI catches up).

**Tests**

- `androidUnitTest/background/EpisodeCheckWorkerTest.kt` — extend to cover the four branches above. Inject a fake `RssFeedClient` returning `Fresh` / `NotModified` and verify the right call shape per branch.

**Emulator exercise**

- Turn on notify for a show. Verify daily-check log shows the RSS path. Force-trigger the worker (`adb shell cmd jobscheduler run -f com.kofikodr.kofipod.foss <jobId>`). Confirm a notification fires on a real new episode.

**Exit criteria.** All gates. Bandwidth budget: instrument the worker with a per-run total-bytes log line and verify ≤ 5 MB on a 10-notify-on subscription day in the worst case (no ETag hosts).

---

## Open questions to settle before B.1

1. **Parser dep — `ksoup` or `xmlutil`?** Both Apache-2.0, both KMP-iOS. `ksoup` is HTML-leaning but parses XML; `xmlutil` is XML-native with cleaner namespace handling. Lean toward `xmlutil` — iTunes namespace handling is the tricky part.
2. **`SourceId.RssDirect`?** Or leave RSS-discovered shows as `SourceId.ITunes` (since that's how the user found them)? Affects the source chip on Library rows.
3. **iTunes-only persistence on first tap, or on subscribe?** B.4 as written persists on first tap. Alternative: lazy-persist only on subscribe, render detail screen entirely from in-memory `RssChannel`. Lazier is cleaner but means the Episode table doesn't accumulate "browsed but never subscribed" rows.
4. **Show "discovered via" attribution in the Podcast Detail header?** Useful UX, but cheap to add later.

---

## Order of work + checkpoints

```
B.1 (parser)           ──► tests + review ──► commit
   ↓
B.2 (fetcher + cache)  ──► tests + review ──► commit + DB version bump
   ↓
B.3 (detail merge)     ──► tests + emulator + review ──► commit
   ↓
B.4 (iTunes nav)       ──► tests + emulator + review ──► commit
   ↓
B.5 (worker)           ──► tests + emulator + review ──► commit
```

Each slice is independently shippable. After B.3, every user who already has the app gets fresher episodes immediately even if B.4 / B.5 never land. After B.4, the iTunes integration from Slice A becomes fully useful. B.5 is a quality-of-life payoff for notify-on users.
