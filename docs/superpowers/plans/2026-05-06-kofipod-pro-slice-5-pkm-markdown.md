# Kofipod Pro — Slice 5: PKM Exports (Markdown) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the universal, zero-auth Markdown export path for snippets, bookmarks, and AI summaries. Pro-gated, foss-flavor unconditional. Establishes the `MarkdownFormatter` contract that Slice 6 (Obsidian + Readwise) will plug behind.

**Architecture:** Pure-Kotlin formatter in `commonMain` (no I/O, fully testable) takes pre-resolved domain types (Snippet/Bookmark/AiSummary + Episode + Podcast) and emits a `MarkdownDocument` value (frontmatter + body + safe filename). A `MarkdownExporter` orchestrator routes the document to two sinks: Copy-to-clipboard (`ClipboardPort` expect/actual) or Share-as-`.md`-file (writes through `MarkdownTempFilePort` expect/actual into `cacheDir/markdown/`, then hands off to the existing `Sharer.shareFile` with MIME `text/markdown`). A small `MarkdownExportSheet` ModalBottomSheet surfaces the two sinks. Three entry points wire it up: long-press a snippet row in Episode Detail's Saved section, long-press a bookmark row (Detail + global Bookmarks screen), and an "Export Markdown" affordance on the AI Summary card. All three gate through the existing `PaywallRouter.requestPaywall(triggerKey)` pattern from `PlayerViewModel`.

**Tech Stack:** Kotlin Multiplatform (commonMain + androidMain + iosMain), Compose Multiplatform (ModalBottomSheet, DropdownMenu), Koin (singletons + parameterized viewModel factories), kotlin.test for commonTest unit coverage. No SQLDelight schema bump this slice — `PkmConnection.sq` and `ExportLog.sq` are deferred to Slice 6 because Markdown is fire-and-forget (no token storage, no idempotency table).

**Schema status:** Current is **18** (post-Slice 3 Snippet table). Slice 5 leaves it at **18**; Slice 6 will introduce 19.

**Out of scope (deferred):**
- SAF persistent URI / "Save .md to a folder" flow — Slice 6 (Obsidian's tree URI infrastructure naturally supports it).
- OAuth, Readwise/Notion adapters, `PkmConnection`/`ExportLog` tables, `PkmExportWorker` — Slice 6 / Slice 9.
- Bulk export ("everything since last sync", per-podcast batch) — Slice 6 plan will add it once destinations + idempotency exist.
- A new Episode Detail kebab/overflow menu — none exists today; Slice 5 places the AI summary export affordance directly on the Summary card to avoid inventing a new UI surface for one action.
- Auto Backup rules update for `ExportLog` — Slice 6.

**Spec references (verbatim):**
- `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` § F3 PKM export pipeline (lines 187–209)
- § "Pro entry points (only these)" (lines 158–165)
- § "Code architecture → New packages → pkm/" (line 317)
- § "Slice plan" Slice 5 row (line 390)

---

## File structure

### Created

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownDocument.kt` | Value type: `frontmatter: List<Pair<String, String>>`, `body: String`, `filename: String`. Insertion-ordered Pair list (not Map) so YAML key order is deterministic for tests. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatter.kt` | Interface. Three methods: `formatSnippet`, `formatBookmark`, `formatAiSummary`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatterImpl.kt` | Pure implementation. No I/O, no clock — `nowMs` and `kofipodId` are passed in. Tests pin behavior. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/Slugger.kt` | `slugify(text: String, maxLen: Int = 32): String` — lowercases, replaces non-alnum runs with `-`, trims, truncates. Used for filenames only (NOT for content). |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/TimestampFormatter.kt` | `formatHms(ms: Long): String` → `H:MM:SS` or `MM:SS` for body text ("Listen at 12:34"). |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/ClipboardPort.kt` | `expect class ClipboardPort { fun copyText(label: String, text: String) }`. |
| `composeApp/src/androidMain/kotlin/app/kofipod/pkm/ClipboardPort.android.kt` | Actual: `android.content.ClipboardManager` via `context.getSystemService`. |
| `composeApp/src/iosMain/kotlin/app/kofipod/pkm/ClipboardPort.ios.kt` | Actual: empty no-op stub (iOS is secondary). |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.kt` | `expect class MarkdownTempFilePort { suspend fun writeTemp(filename: String, content: String): String }` returns the absolute path. |
| `composeApp/src/androidMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.android.kt` | Actual: writes UTF-8 bytes to `context.cacheDir/markdown/<filename>`, mkdirs parent. |
| `composeApp/src/iosMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.ios.kt` | Actual: throws `NotImplementedError("ios")`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownExporter.kt` | Orchestrator. `suspend fun exportToClipboard(MarkdownDocument)` and `suspend fun exportAsFile(MarkdownDocument, shareTitle: String)`. Holds a `Sharer` ref, `ClipboardPort`, `MarkdownTempFilePort`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportRequest.kt` | `sealed interface PkmExportRequest { data class Snippet(snippetId); data class Bookmark(bookmarkId); data class AiSummary(episodeId) }`. Carried as state into the bottom sheet. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportSink.kt` | `enum class PkmExportSink { Clipboard, File }`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportCoordinator.kt` | Process-singleton. Holds `MutableStateFlow<PkmExportRequest?>` for sheet visibility + a single `suspend fun execute(request, sink)` that resolves the domain types via repos, calls the formatter, then calls the exporter. Also publishes a `MutableSharedFlow<PkmExportResult>` (`Copied`, `Shared`, `Failed(message)`) so any host can show a snackbar. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/export/MarkdownExportSheet.kt` | `@Composable fun MarkdownExportSheet(coordinator: PkmExportCoordinator)` — ModalBottomSheet hoisted at AppShell. Two rows: Copy / Share as file. Driven by the coordinator's StateFlow. |
| `composeApp/src/commonTest/kotlin/app/kofipod/pkm/MarkdownFormatterTest.kt` | kotlin.test class. ~10 tests covering snippet / bookmark / aiSummary, frontmatter ordering, empty/null tolerance, special-character escaping in YAML values. |
| `composeApp/src/commonTest/kotlin/app/kofipod/pkm/SluggerTest.kt` | ~6 tests: ASCII passthrough, accent stripping, emoji removal, multi-space collapse, length truncation, blank input → fallback "untitled". |
| `composeApp/src/commonTest/kotlin/app/kofipod/pkm/TimestampFormatterTest.kt` | ~4 tests: < 1m, < 1h, ≥ 1h, 0ms boundary. |
| `composeApp/src/commonTest/kotlin/app/kofipod/pkm/PkmExportCoordinatorTest.kt` | Coordinator tests with fake repos + fake ports — verifies it loads correct domain types per request kind, handles missing rows gracefully, publishes Failed on missing data, publishes Copied / Shared on success. |

### Modified

| Path | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt` | Add `suspend fun selectById(id: String): Bookmark?`. Mirrors `SnippetRepository.selectById`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt` | Add `suspend fun cachedNow(episodeId: String): AiSummary?` — the existing `cachedFor` returns `Flow`; coordinator needs a one-shot read. Implement via `cachedFor(id).firstOrNull()`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt` | `SavedSection(...)` adds `onBookmarkLongPress: (Bookmark) -> Unit` and `onSnippetLongPress: (Snippet) -> Unit` callbacks. Rows wrap their existing `Modifier.clickable` with `combinedClickable(onLongClick = ...)`. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt` | Already accepts `onLongPress` — wire it through to the coordinator (replace the current behaviour, which is delete). Delete moves to a swipe-to-dismiss or stays on a different gesture — see Task 11 for the decision. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt` | Add `onSnippetExportRequested(snippetId)` and `onBookmarkExportRequested(bookmarkId)` and `onAiSummaryExportRequested()` methods. Each runs the canonical `when (pro.state.value) { Pro -> coordinator.show(...); Free, Unknown -> paywallRouter.requestPaywall(triggerKey) }` block from `PlayerViewModel.onBookmarkTapped` (lines 203–221). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt` | Add `onExportRequested(bookmarkId)` with the same gate. |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/SummaryCard.kt` | Add a small text-button "Export as Markdown" beneath the prose, visible only when summary state is `Ready`. Tapping it calls `onExportSummary()` (new param). |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt` | Hoist `MarkdownExportSheet(coordinator)` adjacent to the existing snackbar host so any screen's export request opens it. |
| `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` | `single { MarkdownFormatterImpl() } bind MarkdownFormatter::class`; `single { PkmExportCoordinator(...) }`; `single { MarkdownExporter(get(), get(), get(), get()) }`. Bump `EpisodeDetailViewModel` and `BookmarksViewModel` factories with the new coordinator + paywallRouter deps. |
| `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt` | `single { ClipboardPort(androidContext()) }`; `single { MarkdownTempFilePort(androidContext()) }`. |

### Untouched

- SQLDelight migrations directory (no schema bump).
- `Sharer.kt` / `Sharer.android.kt` — already accepts `mimeType` per Slice 4 work.
- `PaywallRouter` — interface unchanged; only callers grow.
- iOS source set beyond the two stub `.ios.kt` files.

---

## Task list

### Task 1: MarkdownDocument value type + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownDocument.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pkm/MarkdownDocumentTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// MarkdownDocumentTest.kt
package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownDocumentTest {
    @Test
    fun renderProducesYamlFrontmatterAndBody() {
        val doc = MarkdownDocument(
            frontmatter = listOf(
                "podcast" to "Locked On Broncos",
                "episode" to "FCC bans routers",
                "kofipodId" to "snip-abc",
            ),
            body = "Body line.\n\nSecond paragraph.",
            filename = "snip-abc.md",
        )

        val expected = """
            ---
            podcast: "Locked On Broncos"
            episode: "FCC bans routers"
            kofipodId: "snip-abc"
            ---

            Body line.

            Second paragraph.

        """.trimIndent()

        assertEquals(expected, doc.render())
    }

    @Test
    fun renderEscapesQuotesAndBackslashesInValues() {
        val doc = MarkdownDocument(
            frontmatter = listOf("title" to """She said "hi" \ goodbye"""),
            body = "x",
            filename = "x.md",
        )
        // Quotes escaped as \", backslash escaped as \\
        val rendered = doc.render()
        assertEquals(
            """
            ---
            title: "She said \"hi\" \\ goodbye"
            ---

            x

            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun renderHandlesEmptyFrontmatter() {
        val doc = MarkdownDocument(
            frontmatter = emptyList(),
            body = "body only",
            filename = "x.md",
        )
        assertEquals("body only\n", doc.render())
    }

    @Test
    fun frontmatterPreservesInsertionOrder() {
        // YAML key order is part of the formatter's contract — never alphabetize.
        val doc = MarkdownDocument(
            frontmatter = listOf(
                "z" to "1",
                "a" to "2",
                "m" to "3",
            ),
            body = "",
            filename = "x.md",
        )
        val rendered = doc.render()
        val keysInOrder = Regex("""^(\w+):""", RegexOption.MULTILINE)
            .findAll(rendered)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("z", "a", "m"), keysInOrder)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.MarkdownDocumentTest"`
Expected: FAIL with "unresolved reference: MarkdownDocument".

- [ ] **Step 3: Implement MarkdownDocument**

```kotlin
// MarkdownDocument.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Renders to a `.md` blob with optional YAML frontmatter. Frontmatter key
 * order is preserved exactly as supplied (insertion order). Values are quoted
 * and escaped against `"` and `\`.
 *
 * Body is appended verbatim, then a trailing newline is appended if the body
 * does not already end with one. Empty frontmatter omits the `---` block
 * entirely.
 *
 * @property frontmatter ordered key/value pairs; empty list = no frontmatter block.
 * @property body raw markdown body. Caller is responsible for any markdown
 *   escaping inside the body.
 * @property filename safe filename including `.md` extension. Used by file sinks.
 */
data class MarkdownDocument(
    val frontmatter: List<Pair<String, String>>,
    val body: String,
    val filename: String,
) {
    fun render(): String =
        buildString {
            if (frontmatter.isNotEmpty()) {
                append("---\n")
                for ((key, value) in frontmatter) {
                    append(key).append(": \"").append(escape(value)).append("\"\n")
                }
                append("---\n\n")
            }
            append(body)
            if (!body.endsWith("\n")) append('\n')
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.MarkdownDocumentTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Run lint + iOS compile + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
./gradlew :composeApp:compileKotlinIosSimulatorArm64
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownDocument.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/pkm/MarkdownDocumentTest.kt
git commit -m "slice5(pkm): MarkdownDocument value type with deterministic YAML frontmatter"
```

---

### Task 2: Slugger + TimestampFormatter + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/Slugger.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/TimestampFormatter.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pkm/SluggerTest.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pkm/TimestampFormatterTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// SluggerTest.kt
package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class SluggerTest {
    @Test fun lowercasesAndReplacesSpaces() = assertEquals("hello-world", slugify("Hello World"))
    @Test fun stripsPunctuationAndCollapsesRuns() = assertEquals("foo-bar-baz", slugify("Foo!! Bar  Baz"))
    @Test fun stripsAccents() = assertEquals("cafe-au-lait", slugify("Café Au Lait"))
    @Test fun stripsEmoji() = assertEquals("hello-world", slugify("Hello 🌎 World"))
    @Test fun truncatesToMaxLen() = assertEquals("abcdefghij", slugify("abcdefghijklmno", maxLen = 10))
    @Test fun blankFallsBackToUntitled() = assertEquals("untitled", slugify(""))
    @Test fun onlyPunctuationFallsBackToUntitled() = assertEquals("untitled", slugify("!!!---"))
    @Test fun trimmingHyphensAtEdges() = assertEquals("foo-bar", slugify("--foo-bar--"))
}
```

```kotlin
// TimestampFormatterTest.kt
package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampFormatterTest {
    @Test fun zeroIsDoubleZero() = assertEquals("00:00", formatHms(0))
    @Test fun underAMinute() = assertEquals("00:42", formatHms(42_000))
    @Test fun underAnHour() = assertEquals("12:34", formatHms((12 * 60 + 34) * 1_000L))
    @Test fun overAnHour() = assertEquals("1:02:03", formatHms(((1 * 3600) + (2 * 60) + 3) * 1_000L))
    @Test fun roundsDown() = assertEquals("00:01", formatHms(1_999))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.SluggerTest" --tests "app.kofipod.pkm.TimestampFormatterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement Slugger**

```kotlin
// Slugger.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * ASCII-safe filename slug. Used for `.md` filenames only — body content is
 * never slugged.
 *
 * - Lowercases.
 * - Replaces every run of non-alphanumeric (ASCII a-z, 0-9) with a single `-`.
 * - Trims leading/trailing `-`.
 * - Truncates to [maxLen] characters.
 * - Returns `"untitled"` if the result is empty.
 *
 * Accent stripping uses a manual normalize-then-strip because
 * java.text.Normalizer is JVM-only. We strip a curated set of Latin-1 Supplement
 * + Latin Extended-A diacritics; everything outside the BMP letter range
 * (emoji, CJK, etc.) is dropped.
 */
fun slugify(text: String, maxLen: Int = 32): String {
    if (maxLen <= 0) return "untitled"
    val sb = StringBuilder(text.length)
    var prevHyphen = false
    for (ch in text) {
        val mapped = stripDiacritic(ch).lowercaseChar()
        if (mapped in 'a'..'z' || mapped in '0'..'9') {
            sb.append(mapped)
            prevHyphen = false
        } else if (!prevHyphen && sb.isNotEmpty()) {
            sb.append('-')
            prevHyphen = true
        }
    }
    while (sb.isNotEmpty() && sb.last() == '-') sb.deleteCharAt(sb.length - 1)
    if (sb.isEmpty()) return "untitled"
    if (sb.length > maxLen) sb.setLength(maxLen)
    while (sb.isNotEmpty() && sb.last() == '-') sb.deleteCharAt(sb.length - 1)
    return if (sb.isEmpty()) "untitled" else sb.toString()
}

private fun stripDiacritic(c: Char): Char =
    when (c) {
        'à','á','â','ã','ä','å' -> 'a'
        'è','é','ê','ë' -> 'e'
        'ì','í','î','ï' -> 'i'
        'ò','ó','ô','õ','ö' -> 'o'
        'ù','ú','û','ü' -> 'u'
        'ý','ÿ' -> 'y'
        'ñ' -> 'n'
        'ç' -> 'c'
        'À','Á','Â','Ã','Ä','Å' -> 'A'
        'È','É','Ê','Ë' -> 'E'
        'Ì','Í','Î','Ï' -> 'I'
        'Ò','Ó','Ô','Õ','Ö' -> 'O'
        'Ù','Ú','Û','Ü' -> 'U'
        'Ñ' -> 'N'
        'Ç' -> 'C'
        else -> c
    }
```

- [ ] **Step 4: Implement TimestampFormatter**

```kotlin
// TimestampFormatter.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Formats milliseconds as `MM:SS` (under an hour) or `H:MM:SS` (one hour or
 * more). Used in body text, e.g. "Listen at 12:34". Always rounds down.
 */
fun formatHms(ms: Long): String {
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.SluggerTest" --tests "app.kofipod.pkm.TimestampFormatterTest"`
Expected: PASS (8 + 5 = 13/13).

- [ ] **Step 6: Lint + iOS + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
./gradlew :composeApp:compileKotlinIosSimulatorArm64
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/Slugger.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/pkm/TimestampFormatter.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/pkm/SluggerTest.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/pkm/TimestampFormatterTest.kt
git commit -m "slice5(pkm): slugify + formatHms helpers (commonMain, pure)"
```

---

### Task 3: MarkdownFormatter interface + impl + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatter.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatterImpl.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pkm/MarkdownFormatterTest.kt`

This is the central contract. The interface takes pre-resolved domain types so the formatter has zero I/O dependencies — making it trivially unit-testable and reusable from Slice 6's destination adapters.

- [ ] **Step 1: Define the interface**

```kotlin
// MarkdownFormatter.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.domain.Episode
import app.kofipod.domain.Podcast
import app.kofipod.snippets.Snippet

/**
 * Pure markdown formatter — no I/O, no clock, no repos. Caller resolves the
 * domain types and passes them in. Returns a [MarkdownDocument] ready to copy
 * to clipboard or write to a `.md` file.
 *
 * Frontmatter contract (all three formats):
 *   podcast, episode, episodeUrl, timestampMs (where applicable),
 *   createdAt (ISO-8601 from epoch ms), kofipodId, kind.
 *
 * Frontmatter key order is intentionally fixed and verified by tests — Slice 6
 * destination adapters (Obsidian, Readwise) parse this output and break if
 * the order shifts.
 */
interface MarkdownFormatter {
    fun formatSnippet(
        snippet: Snippet,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument

    fun formatBookmark(
        bookmark: Bookmark,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument

    fun formatAiSummary(
        summary: AiSummary,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
// MarkdownFormatterTest.kt
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.ai.AiSummaryEntity
import app.kofipod.ai.AiSummaryLink
import app.kofipod.bookmarks.Bookmark
import app.kofipod.domain.Episode
import app.kofipod.domain.Podcast
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownFormatterTest {
    private val formatter: MarkdownFormatter = MarkdownFormatterImpl()

    private val podcast = Podcast(
        id = "p1",
        title = "Locked On Broncos",
        // ... whatever other required fields the Podcast domain type has;
        // implementer fills in based on actual data class shape
    )

    private val episode = Episode(
        id = "e1",
        podcastId = "p1",
        title = "FCC bans Chinese routers",
        enclosureUrl = "https://example.com/ep1.mp3",
        // ... required fields per real Episode shape
    )

    @Test
    fun snippetFrontmatterCarriesAllExpectedKeysInOrder() {
        val snippet = Snippet(
            id = "snip-mt29",
            episodeId = "e1",
            podcastId = "p1",
            startMs = 60_000,
            endMs = 120_000,
            title = "Best take",
            captionOverride = null,
            createdAtMs = 1_700_000_000_000,
            lastExportFormat = SnippetFormat.MP4,
            lastExportPath = null,
        )

        val doc = formatter.formatSnippet(snippet, episode, podcast)

        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "timestampMs", "durationMs", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        assertEquals("snippet", doc.frontmatter.toMap()["kind"])
        assertEquals("Locked On Broncos", doc.frontmatter.toMap()["podcast"])
        assertEquals("60000", doc.frontmatter.toMap()["timestampMs"])
        assertEquals("60000", doc.frontmatter.toMap()["durationMs"])
        assertEquals("snip-mt29", doc.frontmatter.toMap()["kofipodId"])
    }

    @Test
    fun snippetBodyIncludesTitleHeadingAndJumpLink() {
        val snippet = Snippet(
            id = "snip-mt29",
            episodeId = "e1",
            podcastId = "p1",
            startMs = 754_000, // 12:34
            endMs = 814_000,
            title = "Best take",
            captionOverride = "Listen to the FCC announcement",
            createdAtMs = 1_700_000_000_000,
            lastExportFormat = null,
            lastExportPath = null,
        )

        val body = formatter.formatSnippet(snippet, episode, podcast).body

        assertTrue("title heading missing in body: $body") { body.contains("## Best take") }
        assertTrue("caption missing") { body.contains("Listen to the FCC announcement") }
        assertTrue("hms missing") { body.contains("12:34") }
        assertTrue("episode link missing") { body.contains(episode.enclosureUrl) }
    }

    @Test
    fun snippetWithoutTitleFallsBackToEpisodeTitle() {
        val snippet = sampleSnippet().copy(title = null)
        val body = formatter.formatSnippet(snippet, episode, podcast).body
        assertTrue { body.contains("## ${episode.title}") }
    }

    @Test
    fun bookmarkFrontmatterCarriesAllExpectedKeys() {
        val bookmark = Bookmark(
            id = "bm-7",
            episodeId = "e1",
            podcastId = "p1",
            timestampMs = 754_000,
            note = "Quote: regulators caught up to consumer hardware in '26",
            createdAtMs = 1_700_000_000_000,
        )

        val doc = formatter.formatBookmark(bookmark, episode, podcast)

        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "timestampMs", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        assertEquals("bookmark", doc.frontmatter.toMap()["kind"])
    }

    @Test
    fun bookmarkBodyIncludesNoteAndJumpLink() {
        val bookmark = sampleBookmark().copy(note = "Quote: regulators caught up")
        val body = formatter.formatBookmark(bookmark, episode, podcast).body
        assertTrue { body.contains("Quote: regulators caught up") }
        assertTrue { body.contains("12:34") }
    }

    @Test
    fun bookmarkWithoutNoteOmitsNoteParagraph() {
        val bookmark = sampleBookmark().copy(note = null)
        val body = formatter.formatBookmark(bookmark, episode, podcast).body
        // Body should still contain the jump link, but the note paragraph is gone
        assertTrue { body.contains("12:34") }
        assertTrue("body should not contain stray quote/blockquote when note is null") {
            !body.contains("> ")
        }
    }

    @Test
    fun aiSummaryFrontmatterCarriesAllExpectedKeys() {
        val summary = AiSummary(
            episodeId = "e1",
            summary = "FCC banned a swath of Chinese-made routers...",
            people = emptyList(),
            things = emptyList(),
            links = listOf(AiSummaryLink(label = "FCC announcement", url = "https://fcc.gov/foo")),
            generatedAtMs = 1_700_000_000_000,
        )

        val doc = formatter.formatAiSummary(summary, episode, podcast)

        assertEquals(
            listOf("kind", "podcast", "episode", "episodeUrl", "createdAt", "kofipodId"),
            doc.frontmatter.map { it.first },
        )
        assertEquals("summary", doc.frontmatter.toMap()["kind"])
    }

    @Test
    fun aiSummaryBodyIncludesProseAndSectionsWhenPopulated() {
        val summary = sampleAiSummary().copy(
            summary = "Headline summary.",
            people = listOf(AiSummaryEntity(name = "Sarah", subtitle = "FCC commissioner")),
            things = listOf(AiSummaryEntity(name = "TP-Link AC1750", subtitle = "consumer router")),
            links = listOf(AiSummaryLink(label = "FCC PDF", url = "https://fcc.gov/x.pdf")),
        )
        val body = formatter.formatAiSummary(summary, episode, podcast).body
        assertTrue { body.contains("Headline summary.") }
        assertTrue { body.contains("## People") }
        assertTrue { body.contains("- Sarah — FCC commissioner") }
        assertTrue { body.contains("## Things") }
        assertTrue { body.contains("- TP-Link AC1750 — consumer router") }
        assertTrue { body.contains("## Links") }
        assertTrue { body.contains("[FCC PDF](https://fcc.gov/x.pdf)") }
    }

    @Test
    fun aiSummaryWithEmptyExtrasOmitsEmptySections() {
        val summary = sampleAiSummary().copy(people = emptyList(), things = emptyList(), links = emptyList())
        val body = formatter.formatAiSummary(summary, episode, podcast).body
        assertTrue("People section should be omitted") { !body.contains("## People") }
        assertTrue("Things section should be omitted") { !body.contains("## Things") }
        assertTrue("Links section should be omitted") { !body.contains("## Links") }
    }

    @Test
    fun snippetFilenameUsesSlugAndShortId() {
        val snippet = sampleSnippet().copy(id = "snip-mt29-abcdef", title = "Best!! Take")
        val doc = formatter.formatSnippet(snippet, episode, podcast)
        // <podcast-slug>-<title-slug>-snippet-<short-id>.md
        // exact format is implementer's call but assert these tokens are present + ends in .md
        assertTrue { doc.filename.endsWith(".md") }
        assertTrue { doc.filename.contains("locked-on-broncos") }
        assertTrue { doc.filename.contains("best-take") }
        assertTrue { doc.filename.contains("snippet") }
    }

    private fun sampleSnippet() = Snippet(
        id = "snip-mt29",
        episodeId = "e1",
        podcastId = "p1",
        startMs = 754_000,
        endMs = 814_000,
        title = "Best take",
        captionOverride = null,
        createdAtMs = 1_700_000_000_000,
        lastExportFormat = null,
        lastExportPath = null,
    )

    private fun sampleBookmark() = Bookmark(
        id = "bm-7",
        episodeId = "e1",
        podcastId = "p1",
        timestampMs = 754_000,
        note = null,
        createdAtMs = 1_700_000_000_000,
    )

    private fun sampleAiSummary() = AiSummary(
        episodeId = "e1",
        summary = "",
        people = emptyList(),
        things = emptyList(),
        links = emptyList(),
        generatedAtMs = 1_700_000_000_000,
    )
}
```

> **Implementer note:** the `Podcast` and `Episode` test fixtures need the *real* required fields. Read `composeApp/src/commonMain/kotlin/app/kofipod/domain/Podcast.kt` and `Episode.kt` and fill in any missing constructor args (likely `description`, `imageUrl`, `feedUrl`, etc.). The `AiSummary` shape lives in `app.kofipod.ai`; check the real shape and adjust field names if `generatedAtMs` is named differently. The test should reference the actual types — do not invent fields.

- [ ] **Step 3: Implement MarkdownFormatterImpl**

```kotlin
// MarkdownFormatterImpl.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.domain.Episode
import app.kofipod.domain.Podcast
import app.kofipod.snippets.Snippet
import kotlinx.datetime.Instant

class MarkdownFormatterImpl : MarkdownFormatter {

    override fun formatSnippet(
        snippet: Snippet,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val title = snippet.title?.takeIf { it.isNotBlank() } ?: episode.title
        val frontmatter = listOf(
            "kind" to "snippet",
            "podcast" to podcast.title,
            "episode" to episode.title,
            "episodeUrl" to episode.enclosureUrl,
            "timestampMs" to snippet.startMs.toString(),
            "durationMs" to (snippet.endMs - snippet.startMs).toString(),
            "createdAt" to isoFromEpochMs(snippet.createdAtMs),
            "kofipodId" to snippet.id,
        )
        val body = buildString {
            append("## ").append(title).append("\n\n")
            snippet.captionOverride?.takeIf { it.isNotBlank() }?.let {
                append("> ").append(it).append("\n\n")
            }
            append("Listen at ").append(formatHms(snippet.startMs)).append(" — [")
                .append(episode.title).append("](").append(episode.enclosureUrl).append(")")
        }
        val filename = buildFilename(podcast.title, title, "snippet", snippet.id)
        return MarkdownDocument(frontmatter, body, filename)
    }

    override fun formatBookmark(
        bookmark: Bookmark,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val frontmatter = listOf(
            "kind" to "bookmark",
            "podcast" to podcast.title,
            "episode" to episode.title,
            "episodeUrl" to episode.enclosureUrl,
            "timestampMs" to bookmark.timestampMs.toString(),
            "createdAt" to isoFromEpochMs(bookmark.createdAtMs),
            "kofipodId" to bookmark.id,
        )
        val body = buildString {
            bookmark.note?.takeIf { it.isNotBlank() }?.let {
                append(it).append("\n\n")
            }
            append("Listen at ").append(formatHms(bookmark.timestampMs)).append(" — [")
                .append(episode.title).append("](").append(episode.enclosureUrl).append(")")
        }
        val filename = buildFilename(podcast.title, episode.title, "bookmark", bookmark.id)
        return MarkdownDocument(frontmatter, body, filename)
    }

    override fun formatAiSummary(
        summary: AiSummary,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val frontmatter = listOf(
            "kind" to "summary",
            "podcast" to podcast.title,
            "episode" to episode.title,
            "episodeUrl" to episode.enclosureUrl,
            "createdAt" to isoFromEpochMs(summary.generatedAtMs),
            "kofipodId" to "summary-${episode.id}",
        )
        val body = buildString {
            summary.summary.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            if (summary.people.isNotEmpty()) {
                append("## People\n\n")
                for (e in summary.people) append("- ").append(e.name)
                    .also { e.subtitle?.takeIf { s -> s.isNotBlank() }?.let { sub -> append(" — $sub") } }
                    .also { append("\n") }
                append("\n")
            }
            if (summary.things.isNotEmpty()) {
                append("## Things\n\n")
                for (e in summary.things) append("- ").append(e.name)
                    .also { e.subtitle?.takeIf { s -> s.isNotBlank() }?.let { sub -> append(" — $sub") } }
                    .also { append("\n") }
                append("\n")
            }
            if (summary.links.isNotEmpty()) {
                append("## Links\n\n")
                for (l in summary.links) append("- [").append(l.label).append("](").append(l.url).append(")\n")
                append("\n")
            }
        }
        val filename = buildFilename(podcast.title, episode.title, "summary", episode.id)
        return MarkdownDocument(frontmatter, body, filename.trimEnd())
    }

    private fun buildFilename(
        podcastTitle: String,
        secondaryTitle: String,
        kind: String,
        idForSuffix: String,
    ): String {
        val pSlug = slugify(podcastTitle, maxLen = 24)
        val sSlug = slugify(secondaryTitle, maxLen = 24)
        val shortId = idForSuffix.takeLast(6)
        return "$pSlug-$sSlug-$kind-$shortId.md"
    }

    private fun isoFromEpochMs(ms: Long): String =
        Instant.fromEpochMilliseconds(ms).toString()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.MarkdownFormatterTest"`
Expected: PASS (10/10).

- [ ] **Step 5: Lint + iOS + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
./gradlew :composeApp:compileKotlinIosSimulatorArm64
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatter.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownFormatterImpl.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/pkm/MarkdownFormatterTest.kt
git commit -m "slice5(pkm): MarkdownFormatter contract + impl for snippet/bookmark/summary"
```

---

### Task 4: ClipboardPort expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/ClipboardPort.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/pkm/ClipboardPort.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/pkm/ClipboardPort.ios.kt`

- [ ] **Step 1: Write the expect**

```kotlin
// commonMain/.../ClipboardPort.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Platform clipboard port. Android wraps `ClipboardManager`; iOS is a no-op
 * stub for now (iOS is secondary).
 */
expect class ClipboardPort {
    /** Place [text] on the system clipboard with a human-readable [label]. */
    fun copyText(label: String, text: String)
}
```

- [ ] **Step 2: Write the Android actual**

```kotlin
// androidMain/.../ClipboardPort.android.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual class ClipboardPort(private val context: Context) {
    actual fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
```

- [ ] **Step 3: Write the iOS actual**

```kotlin
// iosMain/.../ClipboardPort.ios.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

actual class ClipboardPort {
    actual fun copyText(label: String, text: String) {
        // iOS: TODO — UIPasteboard.general.string = text once iOS becomes a focus.
    }
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```

Expected: green.

- [ ] **Step 5: Lint + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/ClipboardPort.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/pkm/ClipboardPort.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/pkm/ClipboardPort.ios.kt
git commit -m "slice5(pkm): ClipboardPort expect/actual (Android ClipboardManager; iOS stub)"
```

---

### Task 5: MarkdownTempFilePort expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.ios.kt`

- [ ] **Step 1: Write the expect**

```kotlin
// commonMain/.../MarkdownTempFilePort.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Writes a `.md` blob to a platform-specific cache path and returns the
 * absolute path. The caller is responsible for sharing or otherwise consuming
 * the file. Files placed here are subject to OS cache eviction; do not assume
 * persistence.
 */
expect class MarkdownTempFilePort {
    suspend fun writeTemp(filename: String, content: String): String
}
```

- [ ] **Step 2: Write the Android actual**

```kotlin
// androidMain/.../MarkdownTempFilePort.android.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class MarkdownTempFilePort(private val context: Context) {
    actual suspend fun writeTemp(filename: String, content: String): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "markdown")
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeText(content)
            file.absolutePath
        }
}
```

- [ ] **Step 3: Write the iOS actual**

```kotlin
// iosMain/.../MarkdownTempFilePort.ios.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

actual class MarkdownTempFilePort {
    actual suspend fun writeTemp(filename: String, content: String): String {
        throw NotImplementedError("ios")
    }
}
```

- [ ] **Step 4: Compile + lint + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/pkm/MarkdownTempFilePort.ios.kt
git commit -m "slice5(pkm): MarkdownTempFilePort expect/actual (cacheDir/markdown)"
```

---

### Task 6: BookmarkRepository.selectById + AiSummaryRepository.cachedNow

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt`

- [ ] **Step 1: Add `selectById` to BookmarkRepository**

Locate the existing `BookmarkRepository` (around the `observeForEpisode` method). Add:

```kotlin
/**
 * One-shot fetch by id. Used by the export coordinator to resolve a bookmark
 * referenced from the export sheet without subscribing to a flow.
 */
suspend fun selectById(id: String): Bookmark? =
    withContext(Dispatchers.IO) {
        queries.selectById(id).executeAsOneOrNull()?.toDomain()
    }
```

If `Bookmark.sq` does not already have a `selectById` query, add one mirroring the existing `selectAll` / `selectForEpisode` shape:

```sql
-- Bookmark.sq
selectById:
SELECT * FROM Bookmark WHERE id = :id;
```

- [ ] **Step 2: Add `cachedNow` to AiSummaryRepository**

```kotlin
/**
 * One-shot read of the cached summary. Returns null if no cached row exists
 * or if the cached row is in a non-Ready state.
 *
 * Used by the PKM export coordinator to resolve the summary without
 * subscribing to the [cachedFor] flow.
 */
suspend fun cachedNow(episodeId: String): AiSummary? =
    cachedFor(episodeId).firstOrNull()
```

> Implementer note: confirm `AiSummary` is the correct return type; if `cachedFor` returns `Flow<AiSummaryState>` (a sealed type), unwrap to `AiSummaryState.Ready.summary` and return null otherwise. Read the source before writing.

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/bookmarks/BookmarkRepository.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ai/AiSummaryRepository.kt \
        composeApp/src/commonMain/sqldelight/app/kofipod/db/Bookmark.sq
git commit -m "slice5(pkm): one-shot selectById/cachedNow for export coordinator"
```

---

### Task 7: MarkdownExporter (clipboard + share-as-file)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownExporter.kt`

- [ ] **Step 1: Implement**

```kotlin
// MarkdownExporter.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.share.Sharer

/**
 * Routes a [MarkdownDocument] to one of two sinks:
 *   - clipboard via [ClipboardPort]
 *   - .md file via [MarkdownTempFilePort] + [Sharer.shareFile]
 *
 * Pure orchestrator — no entitlement check (that's the caller's job; see
 * [PkmExportCoordinator]).
 */
class MarkdownExporter(
    private val clipboard: ClipboardPort,
    private val tempFile: MarkdownTempFilePort,
    private val sharer: Sharer,
) {
    fun exportToClipboard(document: MarkdownDocument) {
        clipboard.copyText(label = "Kofipod Markdown", text = document.render())
    }

    suspend fun exportAsFile(document: MarkdownDocument, shareTitle: String) {
        val path = tempFile.writeTemp(document.filename, document.render())
        sharer.shareFile(
            title = shareTitle,
            path = path,
            mimeType = "text/markdown",
            captionText = null,
        )
    }
}
```

- [ ] **Step 2: Compile + lint + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/MarkdownExporter.kt
git commit -m "slice5(pkm): MarkdownExporter routes document to clipboard or share-as-md-file"
```

---

### Task 8: PkmExportRequest / PkmExportSink / PkmExportResult sealed types

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportRequest.kt`

- [ ] **Step 1: Implement**

```kotlin
// PkmExportRequest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/** Identifies which item the user wants to export. */
sealed interface PkmExportRequest {
    data class Snippet(val snippetId: String) : PkmExportRequest
    data class Bookmark(val bookmarkId: String) : PkmExportRequest
    data class AiSummary(val episodeId: String) : PkmExportRequest
}

/** Selected destination from the bottom-sheet. */
enum class PkmExportSink { Clipboard, File }

/** Coordinator → host (snackbar) signal. */
sealed interface PkmExportResult {
    data object Copied : PkmExportResult
    data object Shared : PkmExportResult
    data class Failed(val message: String) : PkmExportResult
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportRequest.kt
git commit -m "slice5(pkm): PkmExportRequest/Sink/Result sealed types"
```

---

### Task 9: PkmExportCoordinator + tests

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportCoordinator.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pkm/PkmExportCoordinatorTest.kt`

The coordinator owns sheet visibility state + executes the request. It pulls Snippet / Bookmark / AiSummary + their Episode + Podcast from the corresponding repos, calls the formatter, then dispatches to the exporter. Errors produce `Failed(message)`.

- [ ] **Step 1: Implement**

```kotlin
// PkmExportCoordinator.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummaryRepository
import app.kofipod.bookmarks.BookmarkRepository
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.snippets.SnippetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide singleton. UI hosts subscribe to [pendingRequest] to know when
 * to show [MarkdownExportSheet]; the sheet calls [execute] when the user picks
 * a sink. Result (Copied / Shared / Failed) is emitted on [results] for
 * snackbar consumers.
 *
 * The coordinator does NOT itself check Pro entitlement — that's the calling
 * ViewModel's job (mirrors the pattern in `PlayerViewModel.onSnipTapped`).
 */
class PkmExportCoordinator(
    private val snippets: SnippetRepository,
    private val bookmarks: BookmarkRepository,
    private val summaries: AiSummaryRepository,
    private val episodes: EpisodesRepository,
    private val library: LibraryRepository,
    private val formatter: MarkdownFormatter,
    private val exporter: MarkdownExporter,
    private val appScope: CoroutineScope,
) {
    private val _pendingRequest = MutableStateFlow<PkmExportRequest?>(null)
    val pendingRequest: StateFlow<PkmExportRequest?> = _pendingRequest

    private val _results = MutableSharedFlow<PkmExportResult>(extraBufferCapacity = 4)
    val results: SharedFlow<PkmExportResult> = _results

    fun show(request: PkmExportRequest) {
        _pendingRequest.value = request
    }

    fun dismiss() {
        _pendingRequest.value = null
    }

    /**
     * Resolve domain types, format, dispatch to sink. Always clears the sheet
     * state, even on failure, so the user is not stuck.
     */
    fun execute(request: PkmExportRequest, sink: PkmExportSink) {
        appScope.launch {
            try {
                val document = buildDocument(request)
                    ?: run {
                        _results.tryEmit(PkmExportResult.Failed("Item not found"))
                        return@launch
                    }
                when (sink) {
                    PkmExportSink.Clipboard -> {
                        exporter.exportToClipboard(document)
                        _results.tryEmit(PkmExportResult.Copied)
                    }
                    PkmExportSink.File -> {
                        exporter.exportAsFile(document, shareTitle = "Share Markdown")
                        _results.tryEmit(PkmExportResult.Shared)
                    }
                }
            } catch (t: Throwable) {
                _results.tryEmit(PkmExportResult.Failed(t.message ?: "Export failed"))
            } finally {
                _pendingRequest.value = null
            }
        }
    }

    private suspend fun buildDocument(request: PkmExportRequest): MarkdownDocument? =
        when (request) {
            is PkmExportRequest.Snippet -> {
                val snippet = snippets.selectById(request.snippetId) ?: return null
                val episode = episodes.episodeNow(snippet.episodeId) ?: return null
                val podcast = library.podcastNow(snippet.podcastId) ?: return null
                formatter.formatSnippet(snippet, episode, podcast)
            }
            is PkmExportRequest.Bookmark -> {
                val bookmark = bookmarks.selectById(request.bookmarkId) ?: return null
                val episode = episodes.episodeNow(bookmark.episodeId) ?: return null
                val podcast = library.podcastNow(bookmark.podcastId) ?: return null
                formatter.formatBookmark(bookmark, episode, podcast)
            }
            is PkmExportRequest.AiSummary -> {
                val summary = summaries.cachedNow(request.episodeId) ?: return null
                val episode = episodes.episodeNow(request.episodeId) ?: return null
                val podcast = library.podcastNow(episode.podcastId) ?: return null
                formatter.formatAiSummary(summary, episode, podcast)
            }
        }
}
```

- [ ] **Step 2: Write tests**

Tests use a TestScope + fake-by-construction repos (anonymous-class fakes are fine because all four repos in this slice are open / can be stubbed via `mockk` if mockk is already in the test classpath, OR via small fake classes that implement only the methods used). Pattern: drive the coordinator end-to-end with a fake formatter + fake exporter and assert (a) `pendingRequest` clears, (b) correct `PkmExportResult` is emitted, (c) failure path emits `Failed`.

> Implementer note: read existing `commonTest` for the project's preferred fake style. If `mockk` is in classpath (check `build.gradle.kts`), prefer it; otherwise hand-roll fake classes per the existing pattern in `app/kofipod/snippets/tests/`.

Required test cases:
1. Snippet request → success → Copied + sheet cleared
2. Bookmark request → success → Shared + sheet cleared
3. AiSummary request → success → Copied + sheet cleared
4. Snippet not found → Failed("Item not found") + sheet cleared
5. Episode missing for valid snippet → Failed + sheet cleared
6. Podcast missing → Failed + sheet cleared
7. Exporter throws → Failed(message) + sheet cleared
8. `dismiss()` clears the sheet without firing any result

- [ ] **Step 3: Run tests**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pkm.PkmExportCoordinatorTest"`
Expected: PASS (8/8).

- [ ] **Step 4: Lint + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/pkm/PkmExportCoordinator.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/pkm/PkmExportCoordinatorTest.kt
git commit -m "slice5(pkm): PkmExportCoordinator + tests (resolve, format, dispatch, results)"
```

---

### Task 10: DI wiring (CommonModule + AndroidModule)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`

- [ ] **Step 1: Register pkm bindings in CommonModule**

In CommonModule.kt's main module, add (next to the existing snippet/bookmark singletons):

```kotlin
// PKM (Slice 5) — Markdown export
single<MarkdownFormatter> { MarkdownFormatterImpl() }
single { MarkdownExporter(get(), get(), get()) }
single {
    PkmExportCoordinator(
        snippets = get(),
        bookmarks = get(),
        summaries = get(),
        episodes = get(),
        library = get(),
        formatter = get(),
        exporter = get(),
        appScope = get(named("appScope")),
    )
}
```

- [ ] **Step 2: Register Android-only ports in AndroidModule**

In `androidPlatformModule` (next to the existing `Sharer` line):

```kotlin
single { ClipboardPort(androidContext()) }
single { MarkdownTempFilePort(androidContext()) }
```

- [ ] **Step 3: Compile + iOS check**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```

Expected: green.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt
git commit -m "slice5(pkm): DI wiring for formatter, exporter, coordinator, ports"
```

---

### Task 11: MarkdownExportSheet ModalBottomSheet UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/export/MarkdownExportSheet.kt`

Minimal bottom sheet driven by `coordinator.pendingRequest`. Two large rows + a Cancel.

- [ ] **Step 1: Implement**

```kotlin
// MarkdownExportSheet.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kofipod.pkm.PkmExportCoordinator
import app.kofipod.pkm.PkmExportSink

/**
 * Hoisted at AppShell. Visible when [PkmExportCoordinator.pendingRequest] is
 * non-null. Two sinks: Copy or Share as file. Dismiss via swipe / Cancel /
 * sheet scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownExportSheet(coordinator: PkmExportCoordinator) {
    val request by coordinator.pendingRequest.collectAsState()
    val current = request ?: return
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { coordinator.dismiss() },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Export as Markdown")
            Spacer(Modifier.height(12.dp))

            ListItem(
                headlineContent = { Text("Copy to clipboard") },
                supportingContent = { Text("Plain Markdown text") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).run {
                    androidx.compose.foundation.clickable {
                        coordinator.execute(current, PkmExportSink.Clipboard)
                    }.let { this then it }
                },
            )
            ListItem(
                headlineContent = { Text("Share as file…") },
                supportingContent = { Text("Sends a .md file via the system share sheet") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).run {
                    androidx.compose.foundation.clickable {
                        coordinator.execute(current, PkmExportSink.File)
                    }.let { this then it }
                },
            )
        }
    }
}
```

> Implementer note: the `Modifier.run { clickable {...}.let { this then it } }` pattern above is awkward; use the project's existing pattern for clickable ListItem rows (search `ListItem.*clickable` in the codebase to find a clean style and mirror it). The intent is: full-width row, tap fires `coordinator.execute(current, sink)`.

- [ ] **Step 2: Hoist in AppShell**

Locate `AppShell.kt` and add (next to the existing snackbar host / quick-add bookmark sheet):

```kotlin
val pkmCoordinator: PkmExportCoordinator = koinInject()
MarkdownExportSheet(coordinator = pkmCoordinator)
```

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```

- [ ] **Step 4: Lint + commit**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/export/MarkdownExportSheet.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt
git commit -m "slice5(pkm): MarkdownExportSheet hoisted at AppShell + Copy/Share rows"
```

---

### Task 12: Wire snippet/bookmark long-press → Pro-gated export in EpisodeDetailViewModel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/SavedSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/EpisodeDetailScreen.kt` (or wherever SavedSection is invoked)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (factory bump)

- [ ] **Step 1: Add gate methods to EpisodeDetailViewModel**

Add three methods using the canonical pattern (mirror `PlayerViewModel.onBookmarkTapped` lines 203–221 verbatim):

```kotlin
fun onSnippetExportRequested(snippetId: String) {
    when (pro.state.value) {
        is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Snippet(snippetId))
        ProEntitlement.Free,
        ProEntitlement.Unknown,
        -> paywallRouter.requestPaywall("paywall_pkm_export_snippet")
    }
}

fun onBookmarkExportRequested(bookmarkId: String) {
    when (pro.state.value) {
        is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Bookmark(bookmarkId))
        ProEntitlement.Free,
        ProEntitlement.Unknown,
        -> paywallRouter.requestPaywall("paywall_pkm_export_bookmark")
    }
}

fun onAiSummaryExportRequested() {
    val episodeId = state.value.episodeId
    when (pro.state.value) {
        is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.AiSummary(episodeId))
        ProEntitlement.Free,
        ProEntitlement.Unknown,
        -> paywallRouter.requestPaywall("paywall_pkm_export_summary")
    }
}
```

Add `pkmExport: PkmExportCoordinator` and (if not already present) `paywallRouter: PaywallRouter` and `pro: ProEntitlementRepository` to the constructor.

- [ ] **Step 2: Update SavedSection to surface long-press**

Change the SavedSection composable signature to accept long-press callbacks. Wrap the snippet and bookmark rows with `combinedClickable(onClick = ..., onLongClick = ...)`. Use `androidx.compose.foundation.combinedClickable` (already used elsewhere in the codebase per the slice 1 detail screen).

- [ ] **Step 3: Wire callbacks in EpisodeDetailScreen**

Hook the new SavedSection callbacks to the new ViewModel methods. Same pattern as the existing `onBookmarkTap = { vm.onBookmarkRowTapped(it.id) }` style.

- [ ] **Step 4: Bump the EpisodeDetailViewModel factory in CommonModule**

The ViewModel's constructor grew; update the matching `viewModel { (id: String) -> ... }` factory with `pkmExport = get()`, `paywallRouter = get()`, `pro = get()` (only the new ones — others stay).

- [ ] **Step 5: Compile + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice5(pkm): Episode Detail saved-section long-press → Pro-gated export sheet"
```

---

### Task 13: Wire bookmark long-press in global Bookmarks screen

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/BookmarksViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (factory bump if needed)

The existing BookmarksScreen already has an `onLongPress` callback wired to delete. **This slice changes that semantics:** long-press now opens the export sheet; delete moves to swipe-to-dismiss (already supported by the row composable per Slice 1).

- [ ] **Step 1: Verify swipe-to-dismiss is already wired**

Read the current row composable. If swipe-to-dismiss is already there, just rebind long-press. If it's not, this slice is **not** responsible for adding it — leave delete on long-press as it is and instead add a small kebab/IconButton on each row that opens the export sheet. Pick the path that requires fewer lines and document the choice in the commit message.

- [ ] **Step 2: Add `onExportRequested` to BookmarksViewModel**

Same gate pattern:

```kotlin
fun onExportRequested(bookmarkId: String) {
    when (pro.state.value) {
        is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Bookmark(bookmarkId))
        ProEntitlement.Free,
        ProEntitlement.Unknown,
        -> paywallRouter.requestPaywall("paywall_pkm_export_bookmark")
    }
}
```

- [ ] **Step 3: Wire from BookmarksScreen**

Replace the existing `onLongPress = { vm.delete(it) }` with `onLongPress = { vm.onExportRequested(it.id) }`. (Or: add an icon if Step 1 went the other way.)

- [ ] **Step 4: Compile + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/bookmarks/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "slice5(pkm): global Bookmarks screen long-press → Pro-gated export sheet"
```

---

### Task 14: AI Summary card "Export as Markdown" affordance

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/ai/SummaryCard.kt` (or whichever file renders the AI summary tab content; locate by grep for `AiSummary.*Ready` in `ui/screens/detail/ai/`)

- [ ] **Step 1: Add export callback param**

```kotlin
@Composable
fun SummaryCard(
    state: AiSummaryState,
    onExportSummary: () -> Unit,  // NEW
    // existing params...
) {
    // existing UI...

    // Below the prose, only when state is Ready:
    if (state is AiSummaryState.Ready) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExportSummary) {
            Text("Export as Markdown")
        }
    }
}
```

- [ ] **Step 2: Wire from detail screen**

Pass `onExportSummary = { vm.onAiSummaryExportRequested() }`.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/
git commit -m "slice5(pkm): AI Summary card 'Export as Markdown' affordance"
```

---

### Task 15: Export-result snackbar host

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`

Subscribe to `coordinator.results` and surface a snackbar.

- [ ] **Step 1: Add LaunchedEffect collector**

```kotlin
val pkmCoordinator: PkmExportCoordinator = koinInject()
val snackbarHostState = /* existing host or remember */
LaunchedEffect(pkmCoordinator) {
    pkmCoordinator.results.collect { result ->
        when (result) {
            PkmExportResult.Copied -> snackbarHostState.showSnackbar("Copied to clipboard")
            PkmExportResult.Shared -> {} // share sheet is its own UI signal
            is PkmExportResult.Failed -> snackbarHostState.showSnackbar("Export failed: ${result.message}")
        }
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:ktlintFormat :composeApp:detekt
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt
git commit -m "slice5(pkm): export-result snackbars (Copied / Failed) at AppShell"
```

---

### Task 16: Full green-check sweep

- [ ] **Step 1: Run all the green checks**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:detekt
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:testFossDebugUnitTest
./gradlew :composeApp:verifyPaparazziDebug
```

All five must pass. If any fail, fix and re-run.

- [ ] **Step 2: Build foss-debug APK**

```bash
./gradlew :composeApp:assembleFossDebug
```

The foss flavor is what the emulator smoke test will exercise (unconditional Pro path, no Play Billing dep).

- [ ] **Step 3: Commit if any green-check fixes were needed**

```bash
git add -A
git commit -m "slice5(pkm): green-check pass (lint/detekt/compile/tests/paparazzi)"
```

---

### Task 17: Emulator end-to-end smoke

**Goal:** verify the full flow on Pixel_9a — long-press a bookmark → bottom sheet appears → tap "Copy" → snackbar fires + clipboard contains the rendered Markdown → re-trigger and tap "Share as file…" → system share sheet shows a `.md` file. Repeat for snippet and AI summary entry points.

- [ ] **Step 1: Boot the emulator**

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &
~/Library/Android/sdk/platform-tools/adb wait-for-device
```

- [ ] **Step 2: Install foss debug**

```bash
./gradlew :composeApp:installFossDebug
```

- [ ] **Step 3: Subscribe to a podcast and create test data**

```bash
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod.foss.debug/.MainActivity
```

Use the device — search → save a podcast → tap into an episode → from the player, tap Snip (creates one snippet draft) → confirm it appears in Saved → tap Bookmark (creates one bookmark) → confirm it appears in Saved → ensure the AI Summary card has a Ready state for at least one episode (if no Gemini key, skip the AI summary export sub-test).

- [ ] **Step 4: Test snippet export**

- Long-press the snippet row in the Saved section → expect ModalBottomSheet to slide up with two rows.
- Tap "Copy to clipboard" → expect snackbar "Copied to clipboard" and sheet dismiss.
- Verify clipboard:

```bash
~/Library/Android/sdk/platform-tools/adb shell service call clipboard 1 \
  s16 "com.android.shell" i32 0 i32 0 | head -50
```

(Or the equivalent simpler approach: paste into a text field on screen and screenshot. The shell call returns binary; use it as a sanity check that something is on the clipboard, but the visual confirmation in a text field is the real signal.)

- Re-long-press → tap "Share as file…" → expect system share sheet listing a `.md` file. Cancel.
- Pull the rendered file from the device:

```bash
~/Library/Android/sdk/platform-tools/adb shell run-as app.kofipod.foss.debug \
  ls cache/markdown/
~/Library/Android/sdk/platform-tools/adb shell run-as app.kofipod.foss.debug \
  cat "cache/markdown/<filename>" > /tmp/kofipod-slice5-snippet.md
```

Open `/tmp/kofipod-slice5-snippet.md` and verify:
- Frontmatter has `kind: "snippet"`, `podcast`, `episode`, `episodeUrl`, `timestampMs`, `durationMs`, `createdAt`, `kofipodId`.
- Body has `## <title>` heading + jump link with HMS.

- [ ] **Step 5: Test bookmark export**

Same pattern from the Saved section. Pull the resulting `.md` and verify:
- Frontmatter has `kind: "bookmark"`.
- Body has the note (or just the jump link if no note).

Also test from the global Bookmarks screen — long-press → sheet → Copy / Share.

- [ ] **Step 6: Test AI summary export (if a Gemini key is configured)**

Open Episode Detail → Summary tab → tap "Export as Markdown" link beneath the prose → sheet appears → tap "Copy to clipboard" → snackbar.

Pull and verify:
- Frontmatter has `kind: "summary"`.
- Body has prose + `## People` / `## Things` / `## Links` sections (where populated).

- [ ] **Step 7: Capture screenshots**

```bash
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/kofipod-slice5-sheet.png
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/kofipod-slice5-snackbar.png
~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/kofipod-slice5-share.png
```

Inspect each visually. The bottom sheet must show two clearly-distinguished rows; the snackbar must read "Copied to clipboard"; the system share sheet must list the `.md` filename.

- [ ] **Step 8: Update memory + commit**

After validation succeeds, update `~/.claude/projects/-Users-ebernie-dev-podman/memory/project_kofipod.md` with a Slice 5 entry summarizing what shipped + any deviations + verification artifacts. Then push:

```bash
git push origin worktree-kofipodpro-pre0
```

---

## Self-review (controller's check before dispatching)

**Spec coverage:**
- [x] § F3 Markdown destination row — implemented as Copy + Share-as-file sinks (SAF save deferred to Slice 6).
- [x] § F3 "exportable units: snippets, bookmarks, AI summaries (the existing free `EpisodeAiSummary` rows)" — three formatter methods, three entry points.
- [x] § F3 Markdown format YAML frontmatter — `MarkdownDocument` value type, deterministic key order.
- [x] § F3 individual export — Tasks 12–14.
- [ ] § F3 bulk export ("per-podcast, per-date-range, everything since last sync") — explicitly deferred.
- [ ] § F3 `ExportLog` idempotency table — deferred (Slice 6 needs it for OAuth sinks; Markdown share is fire-and-forget).
- [ ] § F3 `PkmConnection` table — deferred (no auth state to store yet).
- [ ] § F3 `PkmExportWorker` — deferred (no queued sync needed for clipboard / one-shot share).
- [x] § "Pro entry points" — gate via `paywallRouter.requestPaywall(triggerKey)` mirrors `PlayerViewModel`.
- [x] § "New packages → pkm/" — folder created with formatter + adapters + value types.

**Placeholder scan:**
- Tests reference `Podcast` and `Episode` test fixtures with TODO-style "fill in the real required fields" notes. Implementer is responsible for hydrating them per the actual data class shapes — this is intentional because the project has changed those types between slices and pinning them in the plan would create a stale reference.
- All other code blocks are concrete.

**Type consistency:**
- `MarkdownDocument` shape used by formatter (Task 3), exporter (Task 7), and coordinator (Task 9) — same fields throughout.
- `PkmExportRequest` discriminator used identically in coordinator + sheet + ViewModels.
- `paywallRouter.requestPaywall("paywall_pkm_export_<kind>")` trigger keys are unique per surface so future analytics can attribute conversions.

---

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-05-06-kofipod-pro-slice-5-pkm-markdown.md`.

**Execution mode locked:** Subagent-driven development per the user's standing instruction. Controller dispatches an implementer per task, then a spec reviewer, then a code quality reviewer; fix-pass loops until both approve, then mark task complete and move on. Final task is end-to-end emulator validation. No merge to master per Pro-isolation rule.
