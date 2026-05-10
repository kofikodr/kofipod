// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProSource

/**
 * Maps the domain [ProEntitlement] to the analytics [ProSourceTag]. Lives in
 * the diagnostics package (not pro) so the pro layer stays free of telemetry
 * concerns.
 */
fun ProEntitlement.toTelemetryTag(): ProSourceTag =
    when (this) {
        ProEntitlement.Unknown -> ProSourceTag.UNKNOWN
        ProEntitlement.Free -> ProSourceTag.FREE
        is ProEntitlement.Pro ->
            when (source) {
                ProSource.Individual -> ProSourceTag.INDIVIDUAL
                ProSource.FossBuild -> ProSourceTag.FOSS
                ProSource.ReviewerUnlock -> ProSourceTag.REVIEWER_UNLOCK
            }
    }
