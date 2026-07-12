// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Outcome of sweeping a batch of items where each item is processed in
 * isolation — one item's failure must not abort the rest of the batch.
 */
internal data class IsolatedSweepOutcome(
    val succeeded: Int,
    val failed: Int,
)

/**
 * Runs [action] for every item, isolating failures: a throwing item is counted
 * in [IsolatedSweepOutcome.failed] and the sweep continues with the next item,
 * rather than letting one bad item abort the whole batch (issue #27).
 *
 * [CancellationException] is always rethrown so coroutine cancellation (e.g.
 * WorkManager stopping the worker) is never swallowed and mis-counted as a
 * per-item failure.
 */
internal suspend fun <T> forEachIsolatingFailures(
    items: List<T>,
    action: suspend (T) -> Unit,
): IsolatedSweepOutcome {
    var succeeded = 0
    var failed = 0
    for (item in items) {
        try {
            action(item)
            succeeded++
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            failed++
        }
    }
    return IsolatedSweepOutcome(succeeded = succeeded, failed = failed)
}

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
            // Re-drive downloads deferred by an earlier engine failure (e.g. a
            // background FGS denial) — the flush collector only fires on gate
            // transitions, so this run is their scheduled second chance. For
            // battery-exempted installs the FGS start succeeds right here.
            runCatching { downloads.retryDeferredDownloads() }

            val cap = settings.storageCapBytes().first()
            var totalNew = 0
            var showsWithNew = 0
            val notifyEntries = mutableListOf<Pair<Podcast, Episode>>()
            val now = System.currentTimeMillis()

            // Each feed is refreshed in isolation: a single feed throwing
            // (network/API error, malformed response) must not skip the remaining
            // feeds, cache eviction, run logging, notifications, or the update
            // check. A persistently broken feed simply gets retried on the next
            // scheduled run (issue #27).
            val sweep =
                forEachIsolatingFailures(library.podcastsFlow().first()) { podcast ->
                    val feedId = podcast.id.toLongOrNull() ?: return@forEachIsolatingFailures
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
            if (sweep.failed > 0) {
                Log.w(LOG_TAG, "${sweep.failed} of ${sweep.succeeded + sweep.failed} feeds failed to refresh; continued with the rest")
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

    private companion object {
        const val LOG_TAG = "Kofipod-EpisodeCheck"
    }
}
