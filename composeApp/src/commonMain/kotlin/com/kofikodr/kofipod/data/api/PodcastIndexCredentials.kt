// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/**
 * Podcast Index API credentials. Android reads flavor-scoped AGP BuildConfig so
 * the public FOSS APK can ship empty credentials while Play keeps the maintainer
 * account credentials.
 */
expect object PodcastIndexCredentials {
    val key: String
    val secret: String
}
