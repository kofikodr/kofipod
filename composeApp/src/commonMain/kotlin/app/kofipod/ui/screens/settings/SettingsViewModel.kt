// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.background.Scheduler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.UpdateRepository
import app.kofipod.data.repo.UpdateUiState
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
            updateRepo.state(),
            updateActionFlow,
        ) { base, updateState, action ->
            base.copy(update = updateState, updateAction = action)
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
                .onFailure { updateActionFlow.value = UpdateAction.Error("Check failed: ${it.message ?: "network error"}") }
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
                .onFailure { updateActionFlow.value = UpdateAction.Error("Download failed: ${it.message ?: "unknown"}") }
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
}
