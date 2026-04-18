// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.db.Download
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DownloadsUiState(
    val downloading: List<Download> = emptyList(),
    val queued: List<Download> = emptyList(),
    val completed: List<Download> = emptyList(),
    val failed: List<Download> = emptyList(),
)

class DownloadsViewModel(private val repo: DownloadRepository) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = repo.all()
        .map { all ->
            DownloadsUiState(
                downloading = all.filter { it.state == "Downloading" },
                queued = all.filter { it.state == "Queued" || it.state == "Paused" },
                completed = all.filter { it.state == "Completed" },
                failed = all.filter { it.state == "Failed" },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun cancel(episodeId: String) = repo.cancel(episodeId)
    fun delete(episodeId: String) = repo.delete(episodeId)
}
