// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.ai.AiSummaryUiState
import com.kofikodr.kofipod.ai.MentionedLink
import com.kofikodr.kofipod.ai.MentionedPerson
import com.kofikodr.kofipod.ai.MentionedThing
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.layout.rememberTabletSize
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Mentioned tab body. Pulls the same per-episode [AiSummaryViewModel] the
 * Summary tab uses — Koin returns the cached instance per episodeId — so
 * generating a summary on Summary lights up Mentioned without an extra
 * round-trip.
 *
 * The tab only shows entity content when the cached summary is [AiSummaryUiState.Ready].
 * For Idle/Generating/Error the tab degrades to a small hint pointing the user
 * back to the Summary tab; mirroring the full panel cards there would duplicate
 * the same workflow on two adjacent tabs and confuse the "where do I tap to
 * generate" question.
 */
@Composable
fun MentionedTabPanel(
    episodeId: String,
    modifier: Modifier = Modifier,
    viewModel: AiSummaryViewModel = koinViewModel(parameters = { parametersOf(episodeId) }),
) {
    val state by viewModel.state.collectAsState()
    MentionedTabContent(state = state, modifier = modifier, size = rememberTabletSize())
}

/**
 * Stateless rendering. Lifted out so Paparazzi snapshots can drive each branch
 * with hand-rolled state without standing up Koin or the repository.
 */
@Composable
internal fun MentionedTabContent(
    state: AiSummaryUiState,
    modifier: Modifier = Modifier,
    // Drives the entity-list column count: phone / 8" portrait = 1, 8"
    // landscape / 10" portrait = 2, 10" landscape = 3. The filter chip row
    // above the list stays full-width regardless.
    size: TabletSize? = null,
) {
    when (state) {
        AiSummaryUiState.Hidden -> Unit
        is AiSummaryUiState.Idle -> AwaitingSummaryHint(modifier)
        is AiSummaryUiState.Generating -> AwaitingSummaryHint(modifier)
        is AiSummaryUiState.Error -> AwaitingSummaryHint(modifier)
        is AiSummaryUiState.Ready -> MentionedReady(state.summary, modifier, columnsFor(size))
    }
}

private fun columnsFor(size: TabletSize?): Int =
    when (size) {
        null, TabletSize.Tablet8Port -> 1
        TabletSize.Tablet8Land, TabletSize.Tablet10Port -> 2
        TabletSize.Tablet10Land -> 3
    }

@Composable
private fun AwaitingSummaryHint(modifier: Modifier) {
    val c = LocalKofipodColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(20.dp),
    ) {
        Text(
            "Mentions appear once the summary is generated.",
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Open the Summary tab to start, then come back here for people, books, and links.",
            color = c.textSoft,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

// -----------------------------------------------------------------------------
// Ready: filter row + grouped sections
// -----------------------------------------------------------------------------

private enum class MentionedFilter(val label: String) {
    People("People"),
    Things("Books / things"),
    Links("Links"),
}

@Composable
private fun MentionedReady(
    summary: AiSummary,
    modifier: Modifier,
    columns: Int = 1,
) {
    val total = summary.people.size + summary.things.size + summary.links.size
    if (total == 0) {
        EmptyMentionsHint(modifier)
        return
    }
    // Default selection is the first filter whose section has items so the
    // user lands on something useful rather than an empty pane. People are
    // the most common, but a links-only episode (e.g. a pure show-notes
    // recap) shouldn't open on an empty People list.
    val initial =
        when {
            summary.people.isNotEmpty() -> MentionedFilter.People
            summary.things.isNotEmpty() -> MentionedFilter.Things
            else -> MentionedFilter.Links
        }
    var selected by rememberSaveable { mutableStateOf(initial) }
    // If the underlying summary regenerated and the previously-selected
    // section is now empty, snap back to a non-empty one so we never
    // render the "Nothing in this category" hint as the steady state.
    val activeIsEmpty =
        when (selected) {
            MentionedFilter.People -> summary.people.isEmpty()
            MentionedFilter.Things -> summary.things.isEmpty()
            MentionedFilter.Links -> summary.links.isEmpty()
        }
    if (activeIsEmpty) selected = initial

    Column(modifier.fillMaxWidth()) {
        Header(total = total)
        Spacer(Modifier.height(10.dp))
        FilterRow(
            selected = selected,
            onSelect = { selected = it },
            people = summary.people.size,
            things = summary.things.size,
            links = summary.links.size,
        )
        Spacer(Modifier.height(16.dp))

        when (selected) {
            MentionedFilter.People -> {
                SectionHeader("PEOPLE")
                Spacer(Modifier.height(4.dp))
                if (columns > 1) PeopleGrid(summary.people, columns) else PeopleList(summary.people)
            }
            MentionedFilter.Things -> {
                SectionHeader("BOOKS / THINGS")
                Spacer(Modifier.height(4.dp))
                if (columns > 1) ThingsGrid(summary.things, columns) else ThingsList(summary.things)
            }
            MentionedFilter.Links -> {
                SectionHeader("LINKS")
                Spacer(Modifier.height(4.dp))
                if (columns > 1) LinksGrid(summary.links, columns) else LinksList(summary.links)
            }
        }
    }
}

@Composable
private fun EmptyMentionsHint(modifier: Modifier) {
    val c = LocalKofipodColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(20.dp),
    ) {
        Text(
            "No mentions detected.",
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This episode's summary didn't surface any named people, books, or links.",
            color = c.textSoft,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun Header(total: Int) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "AI",
            color = c.pink,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Mentioned",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            total.toString(),
            color = c.textMute,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun FilterRow(
    selected: MentionedFilter,
    onSelect: (MentionedFilter) -> Unit,
    people: Int,
    things: Int,
    links: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (people > 0) FilterChip(MentionedFilter.People, people, selected, onSelect)
        if (things > 0) FilterChip(MentionedFilter.Things, things, selected, onSelect)
        if (links > 0) FilterChip(MentionedFilter.Links, links, selected, onSelect)
    }
}

/**
 * Sub-filter pill. Selected uses [purpleTint] fill (the lavender from the
 * design — a softer purple than the main tab's solid `c.purple` so the two
 * rows of pills don't fight visually). Unselected uses a subtle desaturated
 * purple outline ([borderStrong]) so the chip strip reads as a single
 * tonal family rather than a second accent colour.
 *
 * The 1dp border is intentionally thinner than the 1.5dp on the main tabs so
 * the sub-row reads as secondary; bumping it back to 1.5dp would flatten the
 * visual hierarchy between the two rows.
 */
@Composable
private fun FilterChip(
    filter: MentionedFilter,
    count: Int,
    selected: MentionedFilter,
    onSelect: (MentionedFilter) -> Unit,
) {
    val c = LocalKofipodColors.current
    val isSelected = filter == selected
    val bg = if (isSelected) c.purpleTint else Color.Transparent
    val fg = if (isSelected) c.purple else c.text
    val borderColor = if (isSelected) c.purpleTint else c.borderStrong
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(999.dp))
                .clickable { onSelect(filter) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("mentionedFilter.${filter.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            filter.label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(),
            color = if (isSelected) fg.copy(alpha = 0.75f) else c.textMute,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val c = LocalKofipodColors.current
    Text(
        title,
        color = c.textMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
}

// -----------------------------------------------------------------------------
// Lists
// -----------------------------------------------------------------------------

// Grid path: chunked rows of `EntityCard` (a boxed variant of EntityRow).
//
// Spec deviation from plan §10.2: the plan called for
// `LazyVerticalGrid(GridCells.Fixed(N))`, but this tab renders inside a parent
// column that already owns vertical scrolling. A `LazyVerticalGrid` nested in
// a `verticalScroll`-bearing parent does not measure — it requires an explicit
// `heightIn(max = ...)` to render, which is brittle (the cap has to be guessed
// or recomputed) and adds no perf gain at this scale. Entity counts per filter
// are typically < 20, so eager composition via chunked Rows is the simpler,
// more robust choice. Revisit only if a filter routinely exceeds ~50 items.
@Composable
private fun PeopleGrid(
    people: List<MentionedPerson>,
    columns: Int,
) {
    val uri = LocalUriHandler.current
    EntityGrid(items = people, columns = columns) { person ->
        EntityCard(
            name = person.name,
            subtitle = person.subtitle,
            onClick = { runCatching { uri.openUri(googleSearchUrl(person.name, person.subtitle)) } },
            testTag = "mentionedPerson",
        )
    }
}

@Composable
private fun ThingsGrid(
    things: List<MentionedThing>,
    columns: Int,
) {
    val uri = LocalUriHandler.current
    EntityGrid(items = things, columns = columns) { thing ->
        EntityCard(
            name = thing.name,
            subtitle = thing.subtitle,
            onClick = { runCatching { uri.openUri(googleSearchUrl(thing.name, thing.subtitle)) } },
            testTag = "mentionedThing",
        )
    }
}

@Composable
private fun LinksGrid(
    links: List<MentionedLink>,
    columns: Int,
) {
    val uri = LocalUriHandler.current
    EntityGrid(items = links, columns = columns) { link ->
        EntityCard(
            name = link.label.ifBlank { link.url },
            subtitle = link.url,
            accent = true,
            onClick = { runCatching { uri.openUri(link.url) } },
            testTag = "mentionedLink",
        )
    }
}

@Composable
private fun <T> EntityGrid(
    items: List<T>,
    columns: Int,
    cell: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) { cell(item) }
                }
                // Pad the trailing row so the cells stay aligned to the leading
                // edge when items.size % columns != 0.
                repeat(columns - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EntityCard(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
    accent: Boolean = false,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp)
            .testTag(testTag),
    ) {
        Text(
            name,
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = if (accent) c.pink else c.textSoft,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun PeopleList(people: List<MentionedPerson>) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        people.forEachIndexed { idx, person ->
            EntityRow(
                name = person.name,
                subtitle = person.subtitle,
                onClick = { runCatching { uri.openUri(googleSearchUrl(person.name, person.subtitle)) } },
                testTag = "mentionedPerson",
            )
            if (idx != people.lastIndex) Divider()
        }
    }
}

@Composable
private fun ThingsList(things: List<MentionedThing>) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        things.forEachIndexed { idx, thing ->
            EntityRow(
                name = thing.name,
                subtitle = thing.subtitle,
                onClick = { runCatching { uri.openUri(googleSearchUrl(thing.name, thing.subtitle)) } },
                testTag = "mentionedThing",
            )
            if (idx != things.lastIndex) Divider()
        }
    }
}

@Composable
private fun LinksList(links: List<MentionedLink>) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        links.forEachIndexed { idx, link ->
            EntityRow(
                name = link.label.ifBlank { link.url },
                subtitle = link.url,
                accent = true,
                onClick = { runCatching { uri.openUri(link.url) } },
                testTag = "mentionedLink",
            )
            if (idx != links.lastIndex) Divider()
        }
    }
}

@Composable
private fun EntityRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
    accent: Boolean = false,
) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = if (accent) c.pink else c.textSoft,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // Drill chevron, not a share icon: the rows navigate into Google /
        // the link's URL — they don't fan content outward to other apps the
        // way Share does. Same chevron the main tab strip uses elsewhere
        // for "tap to go" affordances.
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 14.dp, strokeWidth = 1.6f)
    }
}

@Composable
private fun Divider() {
    val c = LocalKofipodColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.border),
    )
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

/**
 * Builds a `https://www.google.com/search?q=…` URL for a person/thing row tap.
 * Subtitle (when present) gets folded into the query for disambiguation —
 * "Toby Lin independent" is more useful than just "Toby Lin" if the person
 * isn't a top hit on their own. URL-encoded with the small set of chars we
 * actually expect to see; full RFC encoding isn't needed since Google's
 * tolerant.
 */
internal fun googleSearchUrl(
    name: String,
    subtitle: String,
): String {
    val raw = if (subtitle.isBlank()) name else "$name $subtitle"
    return "https://www.google.com/search?q=" + encodeQuery(raw)
}

/**
 * application/x-www-form-urlencoded encoder. Encodes the UTF-8 byte
 * representation of [raw] — not Unicode code points — so non-ASCII names
 * (Cyrillic, CJK, accented Latin, emoji) produce browser-compatible percent
 * escapes (e.g. `é` → `%C3%A9`, not `%E9`) rather than malformed escapes that
 * a Char-based encoder would emit. RFC 3986 unreserved characters
 * (`A-Z`, `a-z`, `0-9`, `-`, `.`, `_`, `~`) pass through; ASCII space maps to
 * `+` per form-encoding, everything else escapes per byte.
 */
private fun encodeQuery(raw: String): String {
    val bytes = raw.encodeToByteArray()
    val sb = StringBuilder(bytes.size * 3)
    for (byte in bytes) {
        when (val v = byte.toInt() and 0xFF) {
            // ASCII unreserved letters/digits: A–Z, a–z, 0–9
            in 0x41..0x5A, in 0x61..0x7A, in 0x30..0x39 -> sb.append(v.toChar())
            // RFC 3986 unreserved punctuation: - . _ ~
            0x2D, 0x2E, 0x5F, 0x7E -> sb.append(v.toChar())
            // Form-encoded space
            0x20 -> sb.append('+')
            else -> {
                sb.append('%')
                sb.append(HEX_DIGITS[v ushr 4])
                sb.append(HEX_DIGITS[v and 0x0F])
            }
        }
    }
    return sb.toString()
}

private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
