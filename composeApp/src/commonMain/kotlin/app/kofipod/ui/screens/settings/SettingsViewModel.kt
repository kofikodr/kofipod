// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.background.Scheduler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.theme.KofipodThemeMode
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
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        repo.themeMode(),
        repo.dailyCheckEnabled(),
        repo.storageCapBytes(),
        repo.skipForwardSeconds(),
        repo.skipBackSeconds(),
    ) { theme, daily, cap, fwd, back ->
        SettingsUiState(theme, daily, cap, fwd, back)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setDailyCheck(on: Boolean) = viewModelScope.launch {
        repo.setDailyCheckEnabled(on)
        if (on) scheduler.enable() else scheduler.disable()
    }
    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }
    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }
    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }
}
