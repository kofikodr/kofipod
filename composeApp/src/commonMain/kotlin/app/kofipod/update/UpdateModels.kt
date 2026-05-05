// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of a successful update check: a stable release whose version is strictly
 * greater than the running app's version. Construction implies "an update is available".
 */
data class UpdateInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val releaseNotes: String,
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("body") val body: String = "",
    @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long = 0L,
    @SerialName("content_type") val contentType: String = "",
)
