// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventTest {

    @Test
    fun `every event name uses snake_case lowercase`() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        every().forEach { event ->
            assertTrue(
                "event name '${event.name}' does not match snake_case",
                pattern.matches(event.name),
            )
        }
    }

    @Test
    fun `every event prop value comes from a known enum vocabulary`() {
        val allowed = setOf("typed", "category", "transcript", "audio")
        every().forEach { event ->
            event.props.values.forEach { value ->
                assertTrue(
                    "prop value '$value' on event '${event.name}' is not from the allowed vocabulary",
                    value in allowed,
                )
            }
        }
    }

    @Test
    fun `app_opened event has stable name and empty props`() {
        val e: TelemetryEvent = TelemetryEvent.AppOpened
        assertEquals("app_opened", e.name)
        assertEquals(emptyMap<String, String>(), e.props)
    }

    @Test
    fun `search_performed event carries source prop`() {
        val e: TelemetryEvent = TelemetryEvent.SearchPerformed(SearchSource.TYPED)
        assertEquals("search_performed", e.name)
        assertEquals(mapOf("source" to "typed"), e.props)
    }

    private fun every(): List<TelemetryEvent> = listOf(
        TelemetryEvent.AppOpened,
        TelemetryEvent.SearchPerformed(SearchSource.TYPED),
        TelemetryEvent.SearchPerformed(SearchSource.CATEGORY),
        TelemetryEvent.EpisodeDownloaded,
        TelemetryEvent.EpisodePlayed,
        TelemetryEvent.AiSummaryGenerated(AiPath.TRANSCRIPT),
        TelemetryEvent.AiSummaryGenerated(AiPath.AUDIO),
        TelemetryEvent.AiDiscussMessageSent(AiPath.TRANSCRIPT),
        TelemetryEvent.AiDiscussMessageSent(AiPath.AUDIO),
    )
}
