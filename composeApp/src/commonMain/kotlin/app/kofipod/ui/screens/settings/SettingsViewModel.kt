// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.auth.AuthService
import app.kofipod.auth.SignInError
import app.kofipod.background.Scheduler
import app.kofipod.config.BuildKonfig
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.theme.KofipodThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
    val backupEnabled: Boolean = false,
    val googleEmail: String? = null,
    val backupSigningIn: Boolean = false,
    val backupError: String? = null,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val auth: AuthService,
) : ViewModel() {
    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(
                repo.themeMode(),
                repo.dailyCheckEnabled(),
                repo.storageCapBytes(),
                repo.skipForwardSeconds(),
                repo.skipBackSeconds(),
            ) { theme, daily, cap, fwd, back ->
                CoreSettings(theme, daily, cap, fwd, back)
            },
            repo.backupEnabled(),
            repo.googleEmail(),
            transient,
        ) { core, backupEnabled, email, t ->
            SettingsUiState(
                themeMode = core.theme,
                dailyCheck = core.daily,
                storageCapBytes = core.cap,
                skipForward = core.fwd,
                skipBack = core.back,
                backupEnabled = backupEnabled,
                googleEmail = email?.takeIf { it.isNotBlank() },
                backupSigningIn = t.signingIn,
                backupError = t.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }

    fun setDailyCheck(on: Boolean) =
        viewModelScope.launch {
            repo.setDailyCheckEnabled(on)
            if (on) scheduler.enable() else scheduler.disable()
        }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }

    fun setBackupEnabled(on: Boolean) {
        if (on) signInAndEnableBackup() else signOutAndDisableBackup()
    }

    fun clearBackupError() {
        transient.value = transient.value.copy(error = null)
    }

    private fun signInAndEnableBackup() {
        if (transient.value.signingIn) return
        transient.value = TransientState(signingIn = true)
        viewModelScope.launch {
            runCatching {
                auth.signIn(BuildKonfig.GOOGLE_SERVER_CLIENT_ID)
            }.onSuccess { r ->
                repo.setGoogleEmail(r.email)
                repo.setBackupEnabled(true)
                transient.value = TransientState()
            }.onFailure { e ->
                transient.value =
                    TransientState(
                        error =
                            when (e) {
                                is SignInError.NotConfigured ->
                                    "Backup needs GOOGLE_SERVER_CLIENT_ID in local.properties to be set."
                                is SignInError.Cancelled -> "Sign-in cancelled."
                                else -> e.message ?: "Sign-in failed."
                            },
                    )
            }
        }
    }

    private fun signOutAndDisableBackup() {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            repo.setBackupEnabled(false)
            repo.setGoogleEmail(null)
            transient.value = TransientState()
        }
    }

    private data class TransientState(
        val signingIn: Boolean = false,
        val error: String? = null,
    )

    private data class CoreSettings(
        val theme: KofipodThemeMode,
        val daily: Boolean,
        val cap: Long,
        val fwd: Int,
        val back: Int,
    )
}
