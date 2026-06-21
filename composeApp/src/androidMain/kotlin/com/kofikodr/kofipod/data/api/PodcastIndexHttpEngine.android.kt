// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Single process-lifetime OkHttp engine shared by every Podcast Index SDK client on Android. Matches
 * the engine the SDK would pick by default (`defaultHttpClientEngineFactory()` → OkHttp), so behaviour
 * is unchanged; the only difference is that it is created once and reused instead of per SDK client.
 * Never closed — that is the intended app-lifetime lifecycle (see [PodcastIndexSharedEngineFactory]).
 */
internal actual val podcastIndexSharedEngine: HttpClientEngine = OkHttp.create { }
