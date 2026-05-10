// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabletSizeTest {
    @Test
    fun `800x1200 classifies as Tablet8Port with IconOnly rail and no master-detail`() {
        val size = classifyTabletSize(800.dp, 1200.dp)
        assertEquals(TabletSize.Tablet8Port, size)
        assertEquals(RailMode.IconOnly, size?.railMode)
        assertEquals(false, size?.isMasterDetail)
    }

    @Test
    fun `1200x800 classifies as Tablet8Land with IconOnly rail and master-detail`() {
        val size = classifyTabletSize(1200.dp, 800.dp)
        assertEquals(TabletSize.Tablet8Land, size)
        assertEquals(RailMode.IconOnly, size?.railMode)
        assertEquals(true, size?.isMasterDetail)
    }

    @Test
    fun `1000x1400 classifies as Tablet10Port with IconLabel rail and no master-detail`() {
        val size = classifyTabletSize(1000.dp, 1400.dp)
        assertEquals(TabletSize.Tablet10Port, size)
        assertEquals(RailMode.IconLabel, size?.railMode)
        assertEquals(false, size?.isMasterDetail)
    }

    @Test
    fun `1400x1000 classifies as Tablet10Land with Expanded rail and master-detail`() {
        val size = classifyTabletSize(1400.dp, 1000.dp)
        assertEquals(TabletSize.Tablet10Land, size)
        assertEquals(RailMode.Expanded, size?.railMode)
        assertEquals(true, size?.isMasterDetail)
    }

    @Test
    fun `phone width below 600 dp returns null`() {
        assertNull(classifyTabletSize(400.dp, 800.dp))
        assertNull(classifyTabletSize(360.dp, 640.dp))
        assertNull(classifyTabletSize(599.dp, 900.dp))
    }

    @Test
    fun `width 599 dp is phone`() {
        // Strict "< 600 dp" per spec §2 — 599.dp is the last phone width.
        assertNull(classifyTabletSize(599.dp, 1000.dp))
    }

    @Test
    fun `width 600 dp is tablet`() {
        // Strict "< 600 dp" per spec §2 — 600.dp is the first tablet width.
        // 600.dp width with portrait orientation, smaller=600 < 900 -> Tablet8Port.
        assertEquals(TabletSize.Tablet8Port, classifyTabletSize(600.dp, 1000.dp))
    }

    @Test
    fun `smaller dimension exactly 900 dp classifies as 10-inch`() {
        // Boundary tie-breaker: smaller >= 900 -> 10".
        assertEquals(TabletSize.Tablet10Port, classifyTabletSize(900.dp, 1200.dp))
        assertEquals(TabletSize.Tablet10Land, classifyTabletSize(1200.dp, 900.dp))
    }

    @Test
    fun `square dimensions are treated as portrait`() {
        assertEquals(TabletSize.Tablet8Port, classifyTabletSize(800.dp, 800.dp))
        assertEquals(TabletSize.Tablet10Port, classifyTabletSize(1000.dp, 1000.dp))
    }
}
