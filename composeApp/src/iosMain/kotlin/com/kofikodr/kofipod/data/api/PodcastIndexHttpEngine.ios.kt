// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/**
 * Single process-lifetime Darwin engine shared by every Podcast Index SDK client on iOS. Matches the
 * SDK's default engine choice on Apple targets; created once and reused instead of per SDK client.
 * Never closed — that is the intended app-lifetime lifecycle (see [PodcastIndexSharedEngineFactory]).
 */
internal actual val podcastIndexSharedEngine: HttpClientEngine = Darwin.create { }
