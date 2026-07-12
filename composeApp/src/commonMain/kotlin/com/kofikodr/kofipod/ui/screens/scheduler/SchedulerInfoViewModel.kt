// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.scheduler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.background.DailyCheckCoordinator
import com.kofikodr.kofipod.background.SchedulerRun
import com.kofikodr.kofipod.background.SchedulerRunLog
import com.kofikodr.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SchedulerInfoUiState(
    val runs: List<SchedulerRun> = emptyList(),
    val dailyEnabled: Boolean = true,
)

/**
 * Backs the Scheduler Details screen. The "daily check" toggle here is load-bearing:
 * besides controlling [com.kofikodr.kofipod.background.EpisodeCheckWorker] registration
 * via [Scheduler], it also gates the periodic SAF backup worker (see
 * [com.kofikodr.kofipod.background.BackupWorker]).
 *
 * The run log is collected reactively off the `KEY_SCHEDULER_RUNS` SyncMeta flow so a
 * worker tick that completes while the screen is open updates the chart without a
 * navigate-away-and-back.
 */
class SchedulerInfoViewModel(
    private val settings: SettingsRepository,
    private val dailyCheck: DailyCheckCoordinator,
) : ViewModel() {
    private val runsFlow =
        settings.metaFlowPublic(SettingsRepository.KEY_SCHEDULER_RUNS)
            .map { SchedulerRunLog.read(settings) }

    val state: StateFlow<SchedulerInfoUiState> =
        combine(runsFlow, settings.dailyCheckEnabled()) { runs, enabled ->
            SchedulerInfoUiState(runs = runs, dailyEnabled = enabled)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SchedulerInfoUiState(
                runs = SchedulerRunLog.read(settings),
                dailyEnabled = settings.getMetaNow(SettingsRepository.KEY_DAILY_CHECK)?.toBoolean() ?: true,
            ),
        )

    fun setDailyCheckEnabled(on: Boolean) =
        viewModelScope.launch {
            dailyCheck.setEnabled(on)
        }
}
