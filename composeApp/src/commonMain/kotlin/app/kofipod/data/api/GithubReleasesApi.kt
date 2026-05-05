// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.api

import app.kofipod.update.GithubRelease
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/**
 * Thin wrapper over the public GitHub releases endpoint. Unauthenticated — limited to
 * 60 requests/hour per IP, which is plenty given our once-per-day check cadence.
 */
class GithubReleasesApi(
    private val client: HttpClient,
) {
    /**
     * Returns the latest stable release, or `null` if the upstream call failed, the
     * repo has no releases, or the latest release is a draft / pre-release.
     */
    suspend fun latestRelease(
        owner: String,
        repo: String,
    ): GithubRelease? {
        val response: HttpResponse =
            runCatching {
                client.get("https://api.github.com/repos/$owner/$repo/releases/latest") {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                }
            }.getOrElse { return null }

        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) return null

        val release: GithubRelease =
            runCatching { response.body<GithubRelease>() }.getOrElse { return null }

        return release.takeUnless { it.draft || it.prerelease }
    }
}
