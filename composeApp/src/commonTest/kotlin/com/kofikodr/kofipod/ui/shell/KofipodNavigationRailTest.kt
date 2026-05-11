// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import com.kofikodr.kofipod.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pure-logic test for the rail's selection rules. Compose UI test infrastructure isn't
 * wired in commonTest in this codebase, so the snapshot tests cover the visual surface
 * and this test covers the no-op-on-reselect mechanic via the extracted helper.
 */
class KofipodNavigationRailTest {
    @Test
    fun `TABS_TABLET lists six destinations in spec order`() {
        val labels = TABS_TABLET.map { it.label }
        assertEquals(
            listOf("Library", "Search", "Downloads", "Stats", "Bookmarks", "Settings"),
            labels,
        )
        assertEquals(6, TABS_TABLET.size)
    }

    @Test
    fun `tapping a non-selected destination returns that destination`() {
        val library = TABS_TABLET.first { it.route == Route.Library }
        val search = TABS_TABLET.first { it.route == Route.Search }
        val result = nextSelection(currentRoute = library.routeKey, tapped = search)
        assertSame(search, result)
    }

    @Test
    fun `tapping the already-selected destination returns null (no-op)`() {
        val library = TABS_TABLET.first { it.route == Route.Library }
        assertNull(nextSelection(currentRoute = library.routeKey, tapped = library))
    }

    @Test
    fun `tapping any destination when no current route is set returns that destination`() {
        val stats = TABS_TABLET.first { it.route == Route.Stats }
        assertSame(stats, nextSelection(currentRoute = null, tapped = stats))
    }

    @Test
    fun `Stats rail destination maps to Route Stats and uses Chart icon`() {
        // Task 1.5 lock-in: Stats is promoted to the tablet rail as a top-level
        // destination (the phone bottom bar still excludes it). If a future
        // refactor reuses TABS_TABLET for both phone and tablet, or swaps the
        // Stats icon, this test forces the change to be intentional.
        val stats = TABS_TABLET.first { it.label == "Stats" }
        assertEquals(Route.Stats, stats.route)
        assertEquals(Route.Stats::class.qualifiedName!!, stats.routeKey)
        assertEquals(com.kofikodr.kofipod.ui.primitives.KPIconName.Chart, stats.icon)
    }
}
