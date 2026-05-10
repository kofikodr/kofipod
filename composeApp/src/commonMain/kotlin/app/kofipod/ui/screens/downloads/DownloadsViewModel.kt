// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.DownloadRepository.Companion.STATE_WAITING_WIFI
import app.kofipod.data.repo.DownloadRow
import app.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DownloadsUiState(
    val downloading: List<DownloadRow> = emptyList(),
    val queued: List<DownloadRow> = emptyList(),
    val completed: List<DownloadRow> = emptyList(),
    val failed: List<DownloadRow> = emptyList(),
    val capBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
)

class DownloadsViewModel(
    private val repo: DownloadRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<DownloadsUiState> =
        combine(repo.allWithMeta(), settings.storageCapBytes()) { all, capBytes ->
            DownloadsUiState(
                downloading = all.filter { it.state == "Downloading" },
                queued = all.filter { it.state == "Queued" || it.state == "Paused" || it.state == STATE_WAITING_WIFI },
                completed = all.filter { it.state == "Completed" },
                failed = all.filter { it.state == "Failed" },
                capBytes = capBytes,
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun cancel(episodeId: String) = repo.cancel(episodeId)

    fun delete(episodeId: String) = repo.delete(episodeId)
}
