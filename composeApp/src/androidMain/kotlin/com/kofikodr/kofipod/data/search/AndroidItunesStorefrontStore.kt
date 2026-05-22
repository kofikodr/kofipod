// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * SharedPreferences-backed implementation of [ItunesStorefrontStore].
 *
 * Lives in the same `kofipod_local` file as other device-local pointers (the SAF
 * backup tree URI, the downloaded-APK pointer). That file is already `<exclude>`-d
 * from Auto Backup in `res/xml/backup_rules*.xml`, so the user's storefront pick
 * stays device-local — adding a new key inherits the exclusion automatically.
 */
class AndroidItunesStorefrontStore(context: Context) : ItunesStorefrontStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun currentNow(): ItunesStorefront = ItunesStorefront.fromIso2OrDefault(prefs.getString(KEY_ISO2, null))

    override fun setStorefront(storefront: ItunesStorefront) {
        prefs.edit().putString(KEY_ISO2, storefront.iso2).apply()
    }

    override fun storefrontFlow(): Flow<ItunesStorefront> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                    if (changedKey == KEY_ISO2 || changedKey == null) {
                        trySend(currentNow())
                    }
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
            .onStart { emit(currentNow()) }
            .distinctUntilChanged()

    companion object {
        // Must stay in sync with the <exclude> rules in res/xml/backup_rules*.xml.
        const val FILE_NAME: String = "kofipod_local"
        private const val KEY_ISO2: String = "itunes_storefront_iso2"
    }
}
