// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import kotlinx.coroutines.flow.Flow

/**
 * Persistent home for the user's chosen iTunes storefront. Device-local (the picker
 * is a per-install preference, not a synced subscription), so on Android it lives in
 * the `kofipod_local` SharedPreferences file alongside the backup tree URI and the
 * downloaded-APK pointer — all already excluded from Auto Backup.
 *
 * Plain interface rather than `expect class` so commonTest can fake it without
 * standing up an Android Context. Android: [AndroidItunesStorefrontStore]. iOS:
 * [IosItunesStorefrontStore] (in-memory stub; iOS is secondary).
 */
interface ItunesStorefrontStore {
    fun currentNow(): ItunesStorefront

    fun setStorefront(storefront: ItunesStorefront)

    fun storefrontFlow(): Flow<ItunesStorefront>
}
