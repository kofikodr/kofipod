// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Current Pro entitlement state for the running user.
 *
 * - [Unknown] is the initial state on cold start, before [BillingClientPort.queryEntitlement]
 *   has produced its first answer. UI MUST treat Unknown as Free for paywall purposes
 *   (i.e. show the paywall sheet on a Pro-gated tap), so a brief Pro-classified user
 *   doesn't get a "blank Pro feature" because the billing query was still in flight.
 *   The mis-classification window is < 1s on a warm device and self-corrects on first
 *   reconciliation.
 * - [Free] is a confirmed-not-Pro state after at least one successful billing query.
 * - [Pro] carries the [source] so analytics + UI can distinguish a paid unlock from a
 *   self-built FOSS unlock without reading multiple flags.
 */
sealed class ProEntitlement {
    data object Unknown : ProEntitlement()

    data object Free : ProEntitlement()

    data class Pro(val source: ProSource) : ProEntitlement()
}

enum class ProSource {
    /** Purchased the [kofipod_pro] SKU on this account. */
    Individual,

    /** Built from source / installed from F-Droid / running the foss flavor. */
    FossBuild,
}
