// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.scheduler

import androidx.lifecycle.ViewModel
import com.kofikodr.kofipod.background.SchedulerRun
import com.kofikodr.kofipod.background.SchedulerRunLog
import com.kofikodr.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SchedulerInfoUiState(
    val runs: List<SchedulerRun> = emptyList(),
    val dailyEnabled: Boolean = true,
)

class SchedulerInfoViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val _state =
        MutableStateFlow(
            SchedulerInfoUiState(
                runs = SchedulerRunLog.read(settings),
                dailyEnabled = settings.getMetaNow(SettingsRepository.KEY_DAILY_CHECK)?.toBoolean() ?: true,
            ),
        )
    val state: StateFlow<SchedulerInfoUiState> = _state.asStateFlow()
}
