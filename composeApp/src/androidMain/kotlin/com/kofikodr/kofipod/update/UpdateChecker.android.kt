// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import com.kofikodr.kofipod.config.AppInfo
import com.kofikodr.kofipod.data.api.GithubReleasesApi
import com.kofikodr.kofipod.data.repo.UpdateRepository
import kotlinx.datetime.Clock

/**
 * Picks the foss APK asset off a GitHub release for the in-app updater.
 *
 * Matches ONLY a flavor-tagged foss APK (the release script attaches
 * `kofipod-foss-<version>-foss-<code>-release.apk`), anchored on the
 * `kofipod-foss-` prefix rather than a substring `-foss-` so a cross-build
 * name like `kofipod-play-foss-compat-*.apk` can't be mis-classified.
 *
 * Returns null when no foss-tagged APK is present — we do NOT fall back to an
 * arbitrary `.apk` (issue #30). The fallback used to exist for pre-flavor-split
 * releases that shipped a single generically-named APK, but the updater always
 * checks the *latest* release, which is always post-split and foss-tagged.
 * Falling back to "the first APK" could offer a play-flavor APK
 * (`applicationId com.kofikodr.kofipod` vs `.foss`) which can't upgrade a foss
 * install — it either installs as a second app or is rejected on signature
 * mismatch. Reporting "no compatible asset" (null) is the safe outcome.
 *
 * The updater is build-time gated to the foss flavor via UpdaterCapability, so
 * a play install never reaches this code path anyway.
 */
internal fun selectFossApkAsset(assets: List<GithubAsset>): GithubAsset? =
    assets.firstOrNull {
        it.name.endsWith(".apk", ignoreCase = true) &&
            it.name.startsWith("kofipod-foss-", ignoreCase = true)
    }

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

        val asset = selectFossApkAsset(release.assets)
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
