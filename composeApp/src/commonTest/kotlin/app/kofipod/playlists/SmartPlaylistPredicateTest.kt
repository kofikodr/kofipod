// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SmartPlaylistPredicateTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    @Test fun emptyPredicateEncodesToEmptyObject() {
        assertEquals("{}", json.encodeToString(SmartPlaylistPredicate.serializer(), SmartPlaylistPredicate()))
    }

    @Test fun roundTripPopulated() {
        val original =
            SmartPlaylistPredicate(
                state = PlayState.Unplayed,
                durationRange = DurationRange(minSec = 600, maxSec = 3600),
                podcastIds = setOf("p1", "p2"),
                maxAgeDays = 7,
                hasTranscript = true,
                downloadedOnly = false,
                hasSnippets = null,
            )
        val wire = json.encodeToString(SmartPlaylistPredicate.serializer(), original)
        val decoded = json.decodeFromString(SmartPlaylistPredicate.serializer(), wire)
        assertEquals(original, decoded)
    }

    @Test fun unknownFieldsIgnoredOnDecode() {
        val wire = """{"state":"Completed","futureField":"ignored"}"""
        val decoded = json.decodeFromString(SmartPlaylistPredicate.serializer(), wire)
        assertEquals(PlayState.Completed, decoded.state)
    }

    @Test fun durationRangeEncodesNullableFields() {
        val onlyMin = DurationRange(minSec = 300, maxSec = null)
        val wire = json.encodeToString(DurationRange.serializer(), onlyMin)
        // explicitNulls = false should suppress the maxSec key.
        assertFalse(wire.contains("maxSec"))
    }
}
