// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastIndexCredentialsTest {
    @Test
    fun fossBuild_doesNotExposePodcastIndexCredentials() {
        assertEquals("", BuildConfig.PODCAST_INDEX_KEY)
        assertEquals("", BuildConfig.PODCAST_INDEX_SECRET)
        assertEquals("", PodcastIndexCredentials.key)
        assertEquals("", PodcastIndexCredentials.secret)
    }
}
