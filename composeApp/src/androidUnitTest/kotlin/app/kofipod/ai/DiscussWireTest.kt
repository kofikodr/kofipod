// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Canary for [DiscussAnswerJson] wire-shape drift. Pairs with
 * `androidUnitTest/resources/ai/sample_discuss_response.json` — when the
 * fixture file changes (added field, renamed key) this test must change in
 * lockstep. Mirrors [AiSummaryJsonTest]'s role for the summary path.
 */
class DiscussWireTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun decodesSampleResponse_intoExpectedFields() {
        val raw = readFixture("ai/sample_discuss_response.json")
        val parsed = json.decodeFromString(DiscussAnswerJson.serializer(), raw)

        assertEquals(2, parsed.citations.size, "Fixture pins two citations — drift here means schema or fixture changed")
        assertEquals("12:34", parsed.citations[0].label)
        assertEquals(754000L, parsed.citations[0].timestampMs)
        assertEquals("18:02", parsed.citations[1].label)
        assertEquals(1082000L, parsed.citations[1].timestampMs)
        assertEquals(true, parsed.answer.startsWith("Toby"), "Answer prose must survive the round-trip — got '${parsed.answer.take(40)}…'")
    }

    @Test
    fun decodesEmptyCitationsArray_cleanly() {
        // The model is allowed to return an empty citations array (e.g. if
        // the transcript has no timestamps). Pin the no-citations case here
        // so a regression that breaks the default doesn't surface only at
        // runtime.
        val raw = """{"answer":"A short reply.","citations":[]}"""
        val parsed = json.decodeFromString(DiscussAnswerJson.serializer(), raw)

        assertEquals("A short reply.", parsed.answer)
        assertEquals(0, parsed.citations.size)
    }

    @Test
    fun toleratesUnknownTopLevelKey() {
        // Forward compat: a future model emits an extra key (e.g. "confidence").
        // The lenient decoder must skip it rather than throw.
        val raw = """{"answer":"Hello.","citations":[],"confidence":0.9}"""
        val parsed = json.decodeFromString(DiscussAnswerJson.serializer(), raw)

        assertEquals("Hello.", parsed.answer)
    }

    private fun readFixture(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Fixture not found on classpath: $path"
        }.use { stream ->
            stream.readBytes().decodeToString()
        }
}
