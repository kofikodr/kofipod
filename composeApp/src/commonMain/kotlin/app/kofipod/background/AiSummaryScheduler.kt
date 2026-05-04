// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

/**
 * Schedules an out-of-process resume of any [PendingAiSummary] markers in the
 * database. Distinct from [Scheduler] (which drives the daily episode-check
 * worker) — this one is one-shot, triggered every time the user taps Generate
 * so a process death mid-pipeline can be recovered without waiting for the
 * next periodic tick.
 *
 * Production implementations:
 *  - Android: [AndroidAiSummaryScheduler] enqueues a unique
 *    [androidx.work.OneTimeWorkRequest] with a `KEEP` policy so back-to-back
 *    taps don't pile up multiple workers. Network constraint matches the
 *    pipeline's actual requirement (Gemini round-trip).
 *  - iOS: [IosAiSummaryScheduler] is a no-op. iOS doesn't have a WorkManager
 *    equivalent and the audio fallback is Android-first per the spec; the
 *    on-init `resumePending()` from [app.kofipod.ai.AiSummaryRepository] is
 *    the only resume mechanism on that platform.
 *
 * Modeled as a `fun interface` so unit tests can drop in a recording fake
 * without standing up a Robolectric context — the only repository call is
 * [enqueueResume], so a single SAM is sufficient.
 */
fun interface AiSummaryScheduler {
    fun enqueueResume()
}
