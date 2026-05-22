// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory implementation of [ItunesStorefrontStore]. iOS is a secondary target —
 * the app's iOS build is not yet shipped, this exists so the Koin graph parity with
 * Android holds and the iOS target compiles. The picker persists for the lifetime of
 * the process only.
 *
 * Backed by a [MutableStateFlow] so [storefrontFlow] re-emits after [setStorefront],
 * matching the Android contract (a one-shot `flowOf` would freeze the Settings row at
 * its initial value on iOS).
 */
class IosItunesStorefrontStore : ItunesStorefrontStore {
    private val state = MutableStateFlow(ItunesStorefront.Default)

    override fun currentNow(): ItunesStorefront = state.value

    override fun setStorefront(storefront: ItunesStorefront) {
        state.value = storefront
    }

    override fun storefrontFlow(): Flow<ItunesStorefront> = state.asStateFlow()
}
