// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences-backed entitlement cache. The file name is referenced by
 * `backup_rules.xml` + `backup_rules_legacy.xml` exclude rules — keep it in sync.
 */
class AndroidEntitlementCache(context: Context) : EntitlementCache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): ProEntitlement? =
        withContext(Dispatchers.IO) {
            val raw = prefs.getString(KEY_TIER, null) ?: return@withContext null
            when (raw) {
                "free" -> ProEntitlement.Free
                "pro_individual" -> ProEntitlement.Pro(ProSource.Individual)
                "pro_family" -> ProEntitlement.Pro(ProSource.Family)
                "pro_foss" -> ProEntitlement.Pro(ProSource.FossBuild)
                else -> null
            }
        }

    override suspend fun write(entitlement: ProEntitlement) {
        if (entitlement is ProEntitlement.Unknown) return
        val raw =
            when (entitlement) {
                ProEntitlement.Unknown -> return
                ProEntitlement.Free -> "free"
                is ProEntitlement.Pro ->
                    when (entitlement.source) {
                        ProSource.Individual -> "pro_individual"
                        ProSource.Family -> "pro_family"
                        ProSource.FossBuild -> "pro_foss"
                    }
            }
        withContext(Dispatchers.IO) {
            prefs.edit { putString(KEY_TIER, raw) }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit { remove(KEY_TIER) }
        }
    }

    companion object {
        const val FILE_NAME = "kofipod_entitlement"
        private const val KEY_TIER = "tier"
    }
}
