// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

/**
 * Build-time switch for surfaces tied to a real billing backend (Play Billing
 * today). Flavor-scoped: only the **play** flavor wires a billing client; the
 * **foss** flavor unconditionally unlocks Pro and excludes Play Billing
 * entirely, so "Restore purchase" has nothing to restore. iOS has no billing
 * integration.
 *
 * Used to hide restore affordances in Settings when there's no purchase to
 * recover.
 */
expect object BillingCapability {
    val restoreEnabled: Boolean
}
