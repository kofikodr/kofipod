// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.auth.AuthService
import app.kofipod.auth.AuthorizationError
import app.kofipod.auth.SignInError
import app.kofipod.background.Scheduler
import app.kofipod.config.BuildKonfig
import app.kofipod.data.backup.BackupRestorer
import app.kofipod.data.backup.BackupSchemaException
import app.kofipod.data.backup.BackupService
import app.kofipod.data.backup.RemoteBlob
import app.kofipod.data.drive.DriveTokenProvider
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
    val backupMessage: String? = null,
    val backupInProgress: Boolean = false,
    val restoreInProgress: Boolean = false,
    val lastBackupAtMillis: Long? = null,
    val remoteBackupVersion: Long? = null,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val auth: AuthService,
    private val backup: BackupService,
    private val restorer: BackupRestorer,
    private val tokens: DriveTokenProvider,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<SettingsUiState> = combine(
        combine(
            repo.themeMode(),
            repo.dailyCheckEnabled(),
            repo.storageCapBytes(),
            repo.skipForwardSeconds(),
            repo.skipBackSeconds(),
        ) { theme, daily, cap, fwd, back ->
            CoreSettings(theme, daily, cap, fwd, back)
        },
        combine(
            repo.backupEnabled(),
            repo.googleEmail(),
            repo.lastBackupAt(),
        ) { enabled, email, last ->
            BackupSettings(enabled, email, last)
        },
        transient,
    ) { core, backup, t ->
        SettingsUiState(
            themeMode = core.theme,
            dailyCheck = core.daily,
            storageCapBytes = core.cap,
            skipForward = core.fwd,
            skipBack = core.back,
            backupEnabled = backup.enabled,
            googleEmail = backup.email?.takeIf { it.isNotBlank() },
            backupSigningIn = t.signingIn,
            backupError = t.error,
            backupMessage = t.message,
            backupInProgress = t.backupInProgress,
            restoreInProgress = t.restoreInProgress,
            lastBackupAtMillis = backup.lastBackup,
            remoteBackupVersion = t.remoteVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }

    fun setDailyCheck(on: Boolean) = viewModelScope.launch {
        repo.setDailyCheckEnabled(on)
        if (on) scheduler.enable() else scheduler.disable()
    }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }
    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }
    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }

    fun setBackupEnabled(on: Boolean) {
        if (on) signInAndEnableBackup() else signOutAndDisableBackup()
    }

    fun clearBackupMessage() {
        transient.value = transient.value.copy(error = null, message = null)
    }

    fun backupNow() {
        if (transient.value.backupInProgress) return
        transient.value = transient.value.copy(
            backupInProgress = true, error = null, message = null,
        )
        viewModelScope.launch {
            runCatching { backup.runBackup() }
                .onSuccess { outcome ->
                    transient.value = transient.value.copy(
                        backupInProgress = false,
                        message = "Backup saved · v${outcome.backupVersion}",
                    )
                }
                .onFailure { e ->
                    transient.value = transient.value.copy(
                        backupInProgress = false,
                        error = e.message ?: "Backup failed.",
                    )
                }
        }
    }

    fun checkRemoteBackup() {
        if (transient.value.restoreInProgress) return
        viewModelScope.launch {
            runCatching { backup.readRemoteBlob() }
                .onSuccess { remote ->
                    transient.value = transient.value.copy(
                        remoteVersion = remote?.blob?.backupVersion,
                    )
                }
                .onFailure { e ->
                    transient.value = transient.value.copy(
                        error = describeBackupError(e),
                    )
                }
        }
    }

    fun restoreNow() {
        if (transient.value.restoreInProgress) return
        transient.value = transient.value.copy(
            restoreInProgress = true, error = null, message = null,
        )
        viewModelScope.launch {
            runCatching {
                val remote: RemoteBlob = backup.readRemoteBlob()
                    ?: throw IllegalStateException("No remote backup found.")
                val summary = restorer.restore(remote.blob)
                remote to summary
            }.onSuccess { (remote, summary) ->
                transient.value = transient.value.copy(
                    restoreInProgress = false,
                    message = "Restored v${remote.blob.backupVersion} · " +
                        "${summary.podcastsInserted + summary.podcastsUpdated} podcasts",
                    remoteVersion = remote.blob.backupVersion,
                )
            }.onFailure { e ->
                transient.value = transient.value.copy(
                    restoreInProgress = false,
                    error = describeBackupError(e),
                )
            }
        }
    }

    private fun signInAndEnableBackup() {
        if (transient.value.signingIn) return
        transient.value = transient.value.copy(signingIn = true, error = null)
        viewModelScope.launch {
            val clientId = BuildKonfig.GOOGLE_SERVER_CLIENT_ID
            runCatching {
                val who = auth.signIn(clientId)
                val drive = auth.authorizeDrive(clientId)
                who to drive
            }.onSuccess { (who, drive) ->
                repo.setGoogleEmail(who.email)
                repo.setDriveAccessToken(drive.accessToken)
                repo.setBackupEnabled(true)
                transient.value = transient.value.copy(signingIn = false)
            }.onFailure { e ->
                transient.value = transient.value.copy(
                    signingIn = false,
                    error = describeAuthError(e),
                )
            }
        }
    }

    private fun signOutAndDisableBackup() {
        viewModelScope.launch {
            // Revoke the Drive access token on Google's side first so a future
            // sign-in prompts fresh consent. Credential Manager clearCredentialState
            // only forgets the local ID-token cache — it doesn't revoke scope grants.
            runCatching { tokens.signOut() }
            runCatching { auth.signOut() }
            repo.setBackupEnabled(false)
            repo.setGoogleEmail(null)
            transient.value = TransientState()
        }
    }

    private fun describeAuthError(e: Throwable): String = when (e) {
        is SignInError.NotConfigured ->
            "Backup needs GOOGLE_SERVER_CLIENT_ID in local.properties to be set."
        is SignInError.Cancelled -> "Sign-in cancelled."
        is AuthorizationError.Cancelled -> "Drive access was not granted."
        is AuthorizationError.NoHostActivity ->
            "Can't show consent — please reopen Settings and try again."
        else -> e.message ?: "Sign-in failed."
    }

    private fun describeBackupError(e: Throwable): String = when (e) {
        is BackupSchemaException -> e.message ?: "Backup is incompatible."
        is AuthorizationError -> describeAuthError(e)
        else -> e.message ?: "Backup operation failed."
    }

    private data class TransientState(
        val signingIn: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val backupInProgress: Boolean = false,
        val restoreInProgress: Boolean = false,
        val remoteVersion: Long? = null,
    )

    private data class CoreSettings(
        val theme: KofipodThemeMode,
        val daily: Boolean,
        val cap: Long,
        val fwd: Int,
        val back: Int,
    )

    private data class BackupSettings(
        val enabled: Boolean,
        val email: String?,
        val lastBackup: Long?,
    )
}
