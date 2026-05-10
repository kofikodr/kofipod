// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

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
        val allowed =
            setOf(
                // SearchSource values
                "typed", "category",
                // AiPath values
                "transcript", "audio",
                // ProSourceTag values (carried by AppOpened.pro_source)
                "unknown", "free", "individual", "foss", "reviewer_unlock",
            )
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
    fun `app_opened event carries pro_source prop`() {
        val e: TelemetryEvent = TelemetryEvent.AppOpened(ProSourceTag.FREE)
        assertEquals("app_opened", e.name)
        assertEquals(mapOf("pro_source" to "free"), e.props)
    }

    @Test
    fun `app_opened pro_source covers all entitlement tiers`() {
        // Pin the wire-format strings so a future refactor can't silently rename
        // a tier and break existing Aptabase queries / cohort filters.
        assertEquals("unknown", ProSourceTag.UNKNOWN.value)
        assertEquals("free", ProSourceTag.FREE.value)
        assertEquals("individual", ProSourceTag.INDIVIDUAL.value)
        assertEquals("foss", ProSourceTag.FOSS.value)
        assertEquals("reviewer_unlock", ProSourceTag.REVIEWER_UNLOCK.value)
    }

    @Test
    fun `reviewer_unlock_applied event has stable name and empty props`() {
        val e: TelemetryEvent = TelemetryEvent.ReviewerUnlockApplied
        assertEquals("reviewer_unlock_applied", e.name)
        assertEquals(emptyMap<String, String>(), e.props)
    }

    @Test
    fun `search_performed event carries source prop`() {
        val e: TelemetryEvent = TelemetryEvent.SearchPerformed(SearchSource.TYPED)
        assertEquals("search_performed", e.name)
        assertEquals(mapOf("source" to "typed"), e.props)
    }

    private fun every(): List<TelemetryEvent> =
        buildList {
            ProSourceTag.values().forEach { add(TelemetryEvent.AppOpened(it)) }
            add(TelemetryEvent.ReviewerUnlockApplied)
            add(TelemetryEvent.SearchPerformed(SearchSource.TYPED))
            add(TelemetryEvent.SearchPerformed(SearchSource.CATEGORY))
            add(TelemetryEvent.EpisodeDownloaded)
            add(TelemetryEvent.EpisodePlayed)
            add(TelemetryEvent.AiSummaryGenerated(AiPath.TRANSCRIPT))
            add(TelemetryEvent.AiSummaryGenerated(AiPath.AUDIO))
            add(TelemetryEvent.AiDiscussMessageSent(AiPath.TRANSCRIPT))
            add(TelemetryEvent.AiDiscussMessageSent(AiPath.AUDIO))
        }
}
