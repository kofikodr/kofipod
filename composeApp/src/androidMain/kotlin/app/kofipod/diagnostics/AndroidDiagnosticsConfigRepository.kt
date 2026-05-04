// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure"
private const val KEY_CRASHES_ENABLED = "diagnostics.crashes.enabled"
private const val KEY_USAGE_ENABLED = "diagnostics.usage.enabled"
private const val KEY_DISCLOSURE_ACK = "diagnostics.disclosure.acknowledged"

/**
 * EncryptedSharedPreferences-backed implementation. Shares the [PREFS_FILE]
 * with [app.kofipod.ai.AndroidKeyVault] — both are excluded from Auto Backup
 * via backup_rules.xml, so flags do not survive device migration. That is
 * the intended fail-safe: a new device always sees disclosure unacknowledged.
 */
class AndroidDiagnosticsConfigRepository(context: Context) : DiagnosticsConfigRepository {
    private val prefs by lazy {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val crashes = MutableStateFlow(prefs.getBoolean(KEY_CRASHES_ENABLED, true))
    private val usage = MutableStateFlow(prefs.getBoolean(KEY_USAGE_ENABLED, true))
    private val ack = MutableStateFlow(prefs.getBoolean(KEY_DISCLOSURE_ACK, false))

    override val crashesEnabled: Flow<Boolean> = crashes.asStateFlow()
    override val usageEnabled: Flow<Boolean> = usage.asStateFlow()
    override val disclosureAcknowledged: Flow<Boolean> = ack.asStateFlow()

    override suspend fun setCrashesEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_CRASHES_ENABLED, enabled).commit()
            crashes.value = enabled
        }

    override suspend fun setUsageEnabled(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_USAGE_ENABLED, enabled).commit()
            usage.value = enabled
        }

    override suspend fun acknowledgeDisclosure() =
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_DISCLOSURE_ACK, true).commit()
            ack.value = true
        }
}
