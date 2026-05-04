// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.GeminiModel
import app.kofipod.background.Scheduler
import app.kofipod.backup.BackupAction
import app.kofipod.backup.BackupController
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.RestoreValidation
import app.kofipod.data.net.NetworkErrorHandler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.UpdateRepository
import app.kofipod.data.repo.UpdateUiState
import app.kofipod.opml.OpmlAction
import app.kofipod.opml.OpmlController
import app.kofipod.playback.PlaybackCache
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.ThemeSystem
import app.kofipod.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase of the user-driven update flow ("user tapped the button"). Distinct from
 * [UpdateUiState] which describes "what the system knows" (latest version, dismissed,
 * downloaded). Together they fully describe the banner's appearance.
 */
sealed interface UpdateAction {
    data object Idle : UpdateAction

    data object Checking : UpdateAction

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateAction

    data class Error(val message: String) : UpdateAction
}

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val streamCacheCapBytes: Long = SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES,
    val streamCacheUsedBytes: Long = 0L,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
    val update: UpdateUiState = UpdateUiState.UpToDate(null),
    val updateAction: UpdateAction = UpdateAction.Idle,
    val aiConnected: Boolean = false,
    val aiModel: GeminiModel = GeminiModel.Flash,
    val opmlAction: OpmlAction = OpmlAction.Idle,
    val backupAction: BackupAction = BackupAction.Idle,
    val backupFolderUri: String? = null,
    val backupFolderName: String? = null,
    val lastBackupAtMs: Long? = null,
    val pendingRestoreConfirm: RestoreValidation.Valid? = null,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
    private val updateChecker: UpdateChecker,
    private val updateRepo: UpdateRepository,
    // Wrapped in an interface so commonMain VM stays Android-free.
    private val updateActions: UpdateActionPort,
    private val aiConfig: AiConfigRepository,
    private val errors: NetworkErrorHandler,
    private val opml: OpmlController,
    private val backup: BackupController,
    private val folderStore: BackupFolderStore,
) : ViewModel() {
    // Refreshes the displayed cache usage once per second while Settings is visible.
    private val cacheUsedFlow =
        flow {
            while (true) {
                emit(playbackCache.sizeBytes())
                delay(1_000)
            }
        }

    private val updateActionFlow = MutableStateFlow<UpdateAction>(UpdateAction.Idle)

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(
                repo.themeMode(),
                repo.dailyCheckEnabled(),
                repo.wifiOnly(),
                repo.storageCapBytes(),
                repo.skipForwardSeconds(),
                repo.skipBackSeconds(),
                repo.streamCacheCapBytes(),
                cacheUsedFlow,
                repo.autoUpdateCheckEnabled(),
            ) { values ->
                SettingsUiState(
                    themeMode = values[0] as KofipodThemeMode,
                    dailyCheck = values[1] as Boolean,
                    wifiOnly = values[2] as Boolean,
                    storageCapBytes = values[3] as Long,
                    skipForward = values[4] as Int,
                    skipBack = values[5] as Int,
                    streamCacheCapBytes = values[6] as Long,
                    streamCacheUsedBytes = values[7] as Long,
                    autoUpdateCheck = values[8] as Boolean,
                )
            },
            combine(
                updateRepo.state(),
                updateActionFlow,
                aiConfig.isKeyConfigured(),
                aiConfig.model(),
                opml.action,
            ) { updateState, action, aiConnected, aiModel, opmlAction ->
                AiAndUpdateState(updateState, action, aiConnected, aiModel, opmlAction)
            },
            combine(
                backup.action,
                folderStore.treeUriFlow(),
                folderStore.lastBackupAtFlow(),
                backup.pendingRestoreConfirm,
            ) { backupAction, treeUri, lastBackupAtMs, pendingRestoreConfirm ->
                BackupSlice(
                    action = backupAction,
                    treeUri = treeUri,
                    folderName = treeUri?.let { folderStore.displayNameForTreeUri(it) },
                    lastBackupAtMs = lastBackupAtMs,
                    pendingRestoreConfirm = pendingRestoreConfirm,
                )
            },
        ) { base, combined, backupSlice ->
            base.copy(
                update = combined.updateState,
                updateAction = combined.action,
                aiConnected = combined.aiConnected,
                aiModel = combined.aiModel,
                opmlAction = combined.opmlAction,
                backupAction = backupSlice.action,
                backupFolderUri = backupSlice.treeUri,
                backupFolderName = backupSlice.folderName,
                lastBackupAtMs = backupSlice.lastBackupAtMs,
                pendingRestoreConfirm = backupSlice.pendingRestoreConfirm,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) =
        viewModelScope.launch {
            repo.setThemeMode(mode)
            themeSystem.apply(mode)
        }

    fun setDailyCheck(on: Boolean) =
        viewModelScope.launch {
            repo.setDailyCheckEnabled(on)
            if (on) scheduler.enable() else scheduler.disable()
        }

    fun setWifiOnly(on: Boolean) = viewModelScope.launch { repo.setWifiOnly(on) }

    fun setAutoUpdateCheck(on: Boolean) = viewModelScope.launch { repo.setAutoUpdateCheckEnabled(on) }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }

    fun setStreamCacheCap(bytes: Long) = viewModelScope.launch { repo.setStreamCacheCapBytes(bytes) }

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }

    fun checkForUpdates() =
        viewModelScope.launch {
            updateActionFlow.value = UpdateAction.Checking
            runCatching { updateChecker.check(force = true) }
                .onSuccess { updateActionFlow.value = UpdateAction.Idle }
                .onFailure {
                    val msg = errors.handle(it, hasCachedData = false, fallback = "network error") ?: "network error"
                    updateActionFlow.value = UpdateAction.Error("Check failed: $msg")
                }
        }

    fun downloadUpdate() {
        val available = (state.value.update as? UpdateUiState.Available)?.info ?: return
        viewModelScope.launch {
            updateActionFlow.value = UpdateAction.Downloading(0L, available.apkSizeBytes)
            runCatching {
                updateActions.downloadApk(available) { downloaded, total ->
                    updateActionFlow.value = UpdateAction.Downloading(downloaded, total)
                }
            }
                .onSuccess { updateActionFlow.value = UpdateAction.Idle }
                .onFailure {
                    val msg = errors.handle(it, hasCachedData = false, fallback = "unknown") ?: "unknown"
                    updateActionFlow.value = UpdateAction.Error("Download failed: $msg")
                }
        }
    }

    fun installUpdate() {
        val ready = state.value.update as? UpdateUiState.ReadyToInstall ?: return
        if (!updateActions.canInstall()) {
            updateActions.openInstallPermissionSettings()
            return
        }
        updateActions.installApk(ready.apkPath)
    }

    fun dismissUpdate() = viewModelScope.launch { updateRepo.dismissCurrentVersion() }

    fun importOpml() = opml.importOpml()

    fun exportOpml() = opml.exportOpml()

    fun chooseBackupFolder() = backup.chooseFolder()

    fun backupNow() = backup.runBackup()

    fun restoreFromBackup() = backup.runRestore()

    fun confirmRestore() = backup.confirmRestore()

    fun cancelRestoreConfirm() = backup.cancelRestoreConfirm()

    fun dismissBackupError() = backup.dismissError()
}

private data class AiAndUpdateState(
    val updateState: UpdateUiState,
    val action: UpdateAction,
    val aiConnected: Boolean,
    val aiModel: GeminiModel,
    val opmlAction: OpmlAction,
)

private data class BackupSlice(
    val action: BackupAction,
    val treeUri: String?,
    val folderName: String?,
    val lastBackupAtMs: Long?,
    val pendingRestoreConfirm: RestoreValidation.Valid?,
)
