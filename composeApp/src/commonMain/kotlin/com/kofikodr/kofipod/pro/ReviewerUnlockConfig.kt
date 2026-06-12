// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

/**
 * Build-time reviewer-unlock secret. Android reads flavor-scoped AGP BuildConfig;
 * non-Android targets keep the hidden reviewer affordance disabled.
 */
expect object ReviewerUnlockConfig {
    val hash: String
}
