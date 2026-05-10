// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadwiseDtosTest {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    @Test
    fun encodeMatchesReadwiseV3Wire() {
        val req =
            ReadwiseCreateRequest(
                highlights =
                    listOf(
                        ReadwiseHighlightCreate(
                            text = "An interesting quote",
                            title = "Episode 42",
                            author = "Show Name",
                            sourceUrl = "https://pod.link/abc",
                            sourceType = "podcast",
                            note = "kofipodId:bookmark-b1",
                            highlightedAt = "2026-05-06T10:00:00Z",
                        ),
                    ),
            )
        val encoded = json.encodeToString(ReadwiseCreateRequest.serializer(), req)
        assertEquals(true, "\"text\":\"An interesting quote\"" in encoded)
        assertEquals(true, "\"source_url\":\"https://pod.link/abc\"" in encoded)
        assertEquals(true, "\"source_type\":\"podcast\"" in encoded)
        assertEquals(true, "\"highlighted_at\":\"2026-05-06T10:00:00Z\"" in encoded)
    }

    @Test
    fun nullableFieldsOmittedWhenAbsent() {
        val req =
            ReadwiseCreateRequest(
                highlights =
                    listOf(
                        ReadwiseHighlightCreate(
                            text = "minimal",
                            title = "ep",
                            sourceUrl = "https://x",
                        ),
                    ),
            )
        val encoded = json.encodeToString(ReadwiseCreateRequest.serializer(), req)
        assertEquals(false, "author" in encoded)
        assertEquals(false, "note" in encoded)
        assertEquals(false, "highlighted_at" in encoded)
    }
}
