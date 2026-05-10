// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodesRepository
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.data.repo.UpdateRepository
import com.kofikodr.kofipod.data.repo.autoDownloadEnabledBool
import com.kofikodr.kofipod.data.repo.notifyNewEpisodesEnabledBool
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.downloadFileName
import com.kofikodr.kofipod.update.UpdateChecker
import com.kofikodr.kofipod.update.UpdaterCapability
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
    private val updateChecker: UpdateChecker by inject()
    private val updateRepo: UpdateRepository by inject()

    override suspend fun doWork(): Result =
        runCatching {
            val cap = settings.storageCapBytes().first()
            var totalNew = 0
            var showsWithNew = 0
            val notifyEntries = mutableListOf<Pair<Podcast, Episode>>()
            val now = System.currentTimeMillis()

            for (podcast in library.podcastsFlow().first()) {
                val feedId = podcast.id.toLongOrNull() ?: continue
                val result = episodes.refresh(podcast.id, feedId, now)
                if (result.inserted > 0) {
                    totalNew += result.inserted
                    showsWithNew++
                    if (podcast.notifyNewEpisodesEnabledBool()) {
                        result.insertedEpisodes.forEach { ep ->
                            notifyEntries += podcast to ep
                        }
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
            postNotification(notifyEntries)

            // Piggyback an app-update check on the same daily run. Gated by the user's
            // Settings → Downloads → "Check for app updates" toggle so opt-out users
            // pay no network cost. Build-time gated by UpdaterCapability so the Play
            // Store flavor compiles the call out entirely. Notify only on the
            // *transition* from "didn't know about this version" to "knows about it" —
            // i.e. don't re-notify each day for the same available release.
            if (UpdaterCapability.enabled && settings.autoUpdateCheckEnabledNow()) {
                runCatching {
                    val previouslyKnown = updateRepo.readUpdateInfoSnapshot()?.version
                    val info = updateChecker.check(force = false)
                    if (info != null &&
                        info.version != previouslyKnown &&
                        updateRepo.dismissedVersionNow() != info.version
                    ) {
                        notifier.postUpdateAvailable(info.version)
                    }
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }

    private suspend fun postNotification(entries: List<Pair<Podcast, Episode>>) {
        when (entries.size) {
            0 -> Unit
            1 -> {
                val (podcast, ep) = entries.single()
                // Per-episode artwork (Podcasting 2.0) wins; falls back to the show's art.
                val art = ep.imageUrl.takeIf { it.isNotBlank() } ?: podcast.artworkUrl
                notifier.postSingleNewEpisode(
                    podcastTitle = podcast.title,
                    episodeTitle = ep.title,
                    episodeId = ep.id,
                    artworkUrl = art.takeIf { it.isNotBlank() },
                )
            }
            else ->
                notifier.postManyNewEpisodes(
                    totalEpisodes = entries.size,
                    totalShows = entries.distinctBy { it.first.id }.size,
                )
        }
    }
}
