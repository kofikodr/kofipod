// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.BuildConfig

actual object PodcastIndexCredentials {
    actual val key: String = BuildConfig.PODCAST_INDEX_KEY
    actual val secret: String = BuildConfig.PODCAST_INDEX_SECRET
}
