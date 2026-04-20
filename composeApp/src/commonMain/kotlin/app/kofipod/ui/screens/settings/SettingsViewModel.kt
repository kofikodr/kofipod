// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.background.Scheduler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.playback.PlaybackCache
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.ThemeSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val streamCacheCapBytes: Long = SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES,
    val streamCacheUsedBytes: Long = 0L,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
) : ViewModel() {
    // Refreshes the displayed cache usage once per second while Settings is visible.
    private val cacheUsedFlow =
        flow {
            while (true) {
                emit(playbackCache.sizeBytes())
                delay(1_000)
            }
        }

    val state: StateFlow<SettingsUiState> =
        combine(
            repo.themeMode(),
            repo.dailyCheckEnabled(),
            repo.wifiOnly(),
            repo.storageCapBytes(),
            repo.skipForwardSeconds(),
            repo.skipBackSeconds(),
            repo.streamCacheCapBytes(),
            cacheUsedFlow,
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
            if (on) scheduler.enable(repo.wifiOnlyNow()) else scheduler.disable()
        }

    fun setWifiOnly(on: Boolean) =
        viewModelScope.launch {
            repo.setWifiOnly(on)
            if (state.value.dailyCheck) scheduler.enable(on)
        }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }

    fun setStreamCacheCap(bytes: Long) = viewModelScope.launch { repo.setStreamCacheCapBytes(bytes) }

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }
}
