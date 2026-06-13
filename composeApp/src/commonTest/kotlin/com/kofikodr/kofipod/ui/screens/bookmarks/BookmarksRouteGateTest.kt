// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.bookmarks

import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookmarksRouteGateTest {
    @Test
    fun freeAndUnknownUsersCannotOpenBookmarksRoute() {
        assertFalse(canOpenBookmarksRoute(ProEntitlement.Free))
        assertFalse(canOpenBookmarksRoute(ProEntitlement.Unknown))
    }

    @Test
    fun proUsersCanOpenBookmarksRoute() {
        assertTrue(canOpenBookmarksRoute(ProEntitlement.Pro(ProSource.Individual)))
        assertTrue(canOpenBookmarksRoute(ProEntitlement.Pro(ProSource.FossBuild)))
        assertTrue(canOpenBookmarksRoute(ProEntitlement.Pro(ProSource.ReviewerUnlock)))
    }
}
