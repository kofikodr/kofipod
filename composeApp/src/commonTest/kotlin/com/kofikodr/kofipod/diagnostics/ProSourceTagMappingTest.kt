// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ProSourceTagMappingTest {
    @Test
    fun `Unknown maps to unknown`() {
        assertEquals(ProSourceTag.UNKNOWN, ProEntitlement.Unknown.toTelemetryTag())
    }

    @Test
    fun `Free maps to free`() {
        assertEquals(ProSourceTag.FREE, ProEntitlement.Free.toTelemetryTag())
    }

    @Test
    fun `Pro Individual maps to individual`() {
        assertEquals(
            ProSourceTag.INDIVIDUAL,
            ProEntitlement.Pro(ProSource.Individual).toTelemetryTag(),
        )
    }

    @Test
    fun `Pro FossBuild maps to foss`() {
        assertEquals(
            ProSourceTag.FOSS,
            ProEntitlement.Pro(ProSource.FossBuild).toTelemetryTag(),
        )
    }

    @Test
    fun `Pro ReviewerUnlock maps to reviewer_unlock`() {
        assertEquals(
            ProSourceTag.REVIEWER_UNLOCK,
            ProEntitlement.Pro(ProSource.ReviewerUnlock).toTelemetryTag(),
        )
    }
}
