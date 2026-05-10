// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import com.kofikodr.kofipod.config.BuildKonfig

/**
 * Build-time switch for the in-app sideload updater (GitHub Releases poll +
 * APK download + install-prompt). The GitHub flavor leaves this on; the Play
 * Store flavor flips it off via `UPDATER_ENABLED=false` in local.properties or
 * the build environment, since Play forbids self-updaters.
 *
 * Gated call sites: the EpisodeCheckWorker piggyback, the SettingsScreen
 * "App update" card, and the auto-update-check toggle.
 */
object UpdaterCapability {
    val enabled: Boolean = BuildKonfig.UPDATER_ENABLED
}
