// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

/**
 * Build-time switch for the in-app sideload updater (GitHub Releases poll +
 * APK download + install-prompt).
 *
 * Flavor-scoped: the **foss** flavor leaves this on (GitHub Releases is the
 * primary distribution channel); the **play** flavor hard-codes it off — Play
 * Store policy forbids self-updaters and the Play APK has a different
 * applicationId (`com.kofikodr.kofipod`) than the foss APK
 * (`com.kofikodr.kofipod.foss`) we'd otherwise try to install. iOS returns
 * false (no APK install path).
 *
 * Gated call sites: the EpisodeCheckWorker piggyback, the SettingsScreen
 * "App update" card, and the auto-update-check toggle.
 */
expect object UpdaterCapability {
    val enabled: Boolean
}
