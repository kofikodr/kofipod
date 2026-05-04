// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

/**
 * iOS production binding for [AiSummaryScheduler]. No background-resume on
 * iOS — the repository's on-init `resumePending()` is the only recovery
 * surface, and audio fallback is gated off via `audioFallbackSupported()` so
 * the marker would only ever cover the (fast, cheap) transcript path.
 */
class IosAiSummaryScheduler : AiSummaryScheduler {
    override fun enqueueResume() {
        // No-op on iOS.
    }
}
