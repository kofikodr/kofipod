// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the phone bottom-nav / tablet-rail tab-switch behaviour (issue #13). Selecting a
 * top-level destination must pop up to the start destination *saving* state, launch
 * single-top, and *restore* state. The old bottom-nav code popped only the top entry,
 * so Library > PodcastDetail > EpisodeDetail then tapping Search left PodcastDetail
 * lingering under Search on Back. These assertions fail if any of those flags regress.
 */
class TabReselectNavOptionsTest {
    @Test
    fun popsUpToStartDestination_savingAndRestoringState_singleTop() {
        val startId = 1234
        val opts = tabReselectNavOptions(startId)

        assertEquals(startId, opts.popUpToId, "must pop up to the start destination, not just the top entry")
        assertFalse(opts.isPopUpToInclusive(), "the start destination itself must remain on the stack")
        assertTrue(opts.shouldPopUpToSaveState(), "the leaving tab's inner stack must be saved")
        assertTrue(opts.shouldLaunchSingleTop(), "re-entering a tab must not stack duplicate copies")
        assertTrue(opts.shouldRestoreState(), "entering a tab must restore its saved inner stack")
    }

    @Test
    fun forwardsTheCallerSuppliedStartDestinationId() {
        // Guards against a hardcoded id: the production call passes the runtime-resolved
        // nav.graph.findStartDestination().id, so the helper must echo whatever it's given.
        assertEquals(9999, tabReselectNavOptions(9999).popUpToId)
        assertEquals(7, tabReselectNavOptions(7).popUpToId)
    }
}
