// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the wire shape Gemini returns when we set
 * `responseMimeType = application/json` plus [SUMMARY_RESPONSE_SCHEMA].
 *
 * The fixture under `resources/ai/sample_response.json` is a recorded
 * real-world response — re-record it (`./scripts/refresh-ai-fixture.sh`,
 * not yet written) when the prompt or schema changes. Until then, treat
 * this test as the canary for schema drift: a CI failure here means the
 * model started ignoring our schema or our DTOs grew incompatible with
 * what the schema produces.
 */
class AiSummaryJsonTest {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesSampleResponse_intoStructuredFields() {
        val raw = readFixture("ai/sample_response.json")

        val parsed = lenientJson.decodeFromString(AiSummaryJson.serializer(), raw)

        assertTrue(
            parsed.summary.startsWith("The hosts unpack"),
            "Summary text must round-trip exactly — drift here means the schema or prompt no longer steers the model",
        )
        assertEquals(
            3,
            parsed.people.size,
            "people array must round-trip three names — a count mismatch means the array got flattened or split",
        )
        assertTrue("Andrei Alexandrescu" in parsed.people)
        assertEquals(4, parsed.things.size, "things must round-trip four titles")
        assertEquals(2, parsed.links.size, "links must round-trip two label/url pairs")
        val rustLink = assertNotNull(parsed.links.firstOrNull { it.label.startsWith("Rust") })
        assertTrue(rustLink.url.startsWith("https://"), "Link URLs must round-trip verbatim, https intact")
    }

    @Test
    fun missingEntityArrays_defaultToEmpty_notParseFailure() {
        // The model occasionally returns just `summary` for an episode with no
        // named entities. Without defaults on AiSummaryJson, that would
        // surface as AiError.Unknown — the prose summary should still land in
        // the Ready card with empty entity sections (which the panel hides).
        val raw = """{"summary":"S"}"""

        val parsed = Json.decodeFromString(AiSummaryJson.serializer(), raw)

        assertEquals("S", parsed.summary)
        assertTrue(parsed.people.isEmpty(), "Missing people field must default to empty list")
        assertTrue(parsed.things.isEmpty())
        assertTrue(parsed.links.isEmpty())
    }

    @Test
    fun unknownFields_areIgnored_so_modelAdditionsDontBreakParsing() {
        // Gemini's structured-output behaviour is stable but not contractual.
        // A future minor revision that adds `confidence` next to each entity
        // (or a top-level `usageMetadata`) must NOT break parsing — otherwise
        // every successful generation surfaces as Unknown the moment Google
        // ships the change. The aiJson instance inside GeminiClient sets
        // `ignoreUnknownKeys = true`; this test pins that contract.
        val raw =
            """
            {
              "summary": "S",
              "confidence": 0.91,
              "people": ["A"],
              "things": [],
              "links": [],
              "usageMetadata": {"promptTokens": 1234}
            }
            """.trimIndent()

        val parsed = lenientJson.decodeFromString(AiSummaryJson.serializer(), raw)

        assertEquals("S", parsed.summary)
        assertEquals(listOf("A"), parsed.people)
    }

    private fun readFixture(path: String): String {
        val resource = javaClass.classLoader!!.getResourceAsStream(path)
        assertNotNull(resource, "Fixture not found on classpath: $path")
        return resource.bufferedReader().use { it.readText() }
    }
}
