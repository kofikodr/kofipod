// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.autoDownloadEnabledBool
import app.kofipod.data.repo.notifyNewEpisodesEnabledBool
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.downloadFileName
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EpisodeCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val library: LibraryRepository by inject()
    private val episodes: EpisodesRepository by inject()
    private val settings: SettingsRepository by inject()
    private val downloads: DownloadRepository by inject()
    private val notifier: Notifier by inject()

    override suspend fun doWork(): Result =
        runCatching {
            val cap = settings.storageCapBytes().first()
            var totalNew = 0
            var showsWithNew = 0
            var notifyNew = 0
            var notifyShows = 0
            val now = System.currentTimeMillis()

            for (podcast in library.podcastsFlow().first()) {
                val feedId = podcast.id.toLongOrNull() ?: continue
                val result = episodes.refresh(podcast.id, feedId, now)
                if (result.inserted > 0) {
                    totalNew += result.inserted
                    showsWithNew++
                    if (podcast.notifyNewEpisodesEnabledBool()) {
                        notifyNew += result.inserted
                        notifyShows++
                    }
                    if (podcast.autoDownloadEnabledBool()) {
                        result.insertedEpisodes.forEach { ep ->
                            downloads.enqueue(
                                episodeId = ep.id,
                                url = ep.enclosureUrl,
                                fileName = downloadFileName(ep.id, ep.enclosureMimeType),
                                source = DownloadJob.Source.Auto,
                            )
                        }
                    }
                }
            }

            downloads.evictUntilUnderCap(cap)
            SchedulerRunLog.append(
                settings,
                SchedulerRun(at = now, inserted = totalNew, shows = showsWithNew),
            )
            if (notifyNew > 0) notifier.postNewEpisodes(notifyNew, notifyShows)
            Result.success()
        }.getOrElse { Result.retry() }
}
