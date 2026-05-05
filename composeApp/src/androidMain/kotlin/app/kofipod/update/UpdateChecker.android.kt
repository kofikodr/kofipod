// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import app.kofipod.config.AppInfo
import app.kofipod.data.api.GithubReleasesApi
import app.kofipod.data.repo.UpdateRepository
import kotlinx.datetime.Clock

actual class UpdateChecker(
    private val api: GithubReleasesApi,
    private val repo: UpdateRepository,
) {
    actual suspend fun check(force: Boolean): UpdateInfo? {
        val now = Clock.System.now().toEpochMilliseconds()
        val last = repo.lastCheckAtMsNow()
        if (!force && last != null && (now - last) < UpdateConfig.CHECK_INTERVAL_MS) {
            // Throttled — caller (worker) treats null as "nothing new this run", which
            // prevents the daily worker from re-notifying about an already-known version
            // every time it wakes up. The Settings UI re-renders from the persisted state
            // independently of this return value.
            return null
        }

        val release = api.latestRelease(UpdateConfig.OWNER, UpdateConfig.REPO)
        repo.markChecked(now)

        if (release == null) return repo.readUpdateInfoSnapshot()

        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (asset == null) {
            // Tagged release with no APK asset — nothing to install.
            return null
        }

        val tag = release.tagName
        val current = AppInfo.versionName
        if (compareSemver(tag, current) <= 0) {
            // Up to date or older. Wipe any stale "available" hint so the banner clears
            // after a user updates manually.
            repo.clearAvailable()
            return null
        }

        val info =
            UpdateInfo(
                version = tag.trim().removePrefix("v").removePrefix("V"),
                releaseUrl = release.htmlUrl,
                apkUrl = asset.browserDownloadUrl,
                apkSizeBytes = asset.size,
                releaseNotes = release.body,
            )
        repo.storeAvailable(info)
        return info
    }
}
