// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.background.Scheduler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.ThemeSystem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(
            repo.themeMode(),
            repo.dailyCheckEnabled(),
            repo.wifiOnly(),
            repo.storageCapBytes(),
            repo.skipForwardSeconds(),
            repo.skipBackSeconds(),
        ) { values ->
            SettingsUiState(
                themeMode = values[0] as KofipodThemeMode,
                dailyCheck = values[1] as Boolean,
                wifiOnly = values[2] as Boolean,
                storageCapBytes = values[3] as Long,
                skipForward = values[4] as Int,
                skipBack = values[5] as Int,
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

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }
}
