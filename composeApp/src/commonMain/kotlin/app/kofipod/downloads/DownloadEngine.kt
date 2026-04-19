// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.SharedFlow

data class DownloadJob(
    val episodeId: String,
    val url: String,
    val targetFileName: String,
    val source: Source,
) {
    enum class Source { Auto, Manual }
}

data class DownloadProgress(
    val episodeId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val state: State,
    val errorMessage: String? = null,
) {
    enum class State { Queued, Downloading, Completed, Failed, Paused }
}

expect class DownloadEngine {
    val events: SharedFlow<DownloadProgress>

    fun enqueue(job: DownloadJob)

    fun cancel(episodeId: String)

    fun delete(episodeId: String)
}
