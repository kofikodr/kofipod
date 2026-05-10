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
    fun `TABS_TABLET lists five destinations in spec order`() {
        val labels = TABS_TABLET.map { it.label }
        assertEquals(listOf("Library", "Search", "Downloads", "Stats", "Settings"), labels)
        assertEquals(5, TABS_TABLET.size)
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
}
