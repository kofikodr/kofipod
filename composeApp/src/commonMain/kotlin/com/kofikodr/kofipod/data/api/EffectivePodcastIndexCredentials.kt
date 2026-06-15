// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/**
 * Resolves which Podcast Index credentials are actually in effect: user-supplied BYOK creds
 * when configured and usable, otherwise the build-time [PodcastIndexCredentials] (blank on FOSS,
 * the maintainer key on Play). [buildTime] is injectable so this is unit-testable without BuildConfig.
 */
class EffectivePodcastIndexCredentials(
    private val config: PodcastIndexConfigRepository,
    private val buildTime: PodcastIndexCreds =
        PodcastIndexCreds(PodcastIndexCredentials.key, PodcastIndexCredentials.secret),
) {
    suspend fun resolve(): PodcastIndexCreds = config.currentCreds()?.takeIf { it.isUsable } ?: buildTime
}
