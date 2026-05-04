// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * A breadcrumb shape decoupled from Sentry SDK types so the scrubber stays
 * in commonMain. The Android CrashReporter adapts Sentry's Breadcrumb to/from this.
 */
data class Breadcrumb(
    val category: String,
    val message: String,
    val data: Map<String, String>,
)

object CrashReporterScrubber {

    private val urlWithQueryRegex = Regex("""(https?://[^\s?]+)\?[^\s]*""")

    private val sensitiveHttpHosts = listOf("gemini", "googleapis", "podcastindex")

    private val droppedCategories = setOf("query")

    fun scrubMessage(raw: String): String =
        urlWithQueryRegex.replace(raw) { match -> match.groupValues[1] }

    fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb? {
        if (crumb.category in droppedCategories) return null
        if (crumb.category == "http") {
            val haystack = (crumb.message + " " + crumb.data.values.joinToString(" ")).lowercase()
            if (sensitiveHttpHosts.any { it in haystack }) return null
        }
        return crumb.copy(
            message = scrubMessage(crumb.message),
            data = crumb.data.mapValues { (_, v) -> scrubMessage(v) },
        )
    }
}
