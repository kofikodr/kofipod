// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback

/**
 * Allowlist gate for `MediaSession.ControllerInfo` connections to
 * [KofipodPlaybackService] — the service is exported (it has to be for
 * Android Auto / system media notification to bind to it), which makes
 * `onGetSession` reachable from every installed app on the device.
 * Without a gate, an untrusted local app can bind as a `MediaBrowser`
 * client and walk the library tree (subscribed shows, episode IDs,
 * playback queue, current position), and send transport-control intents.
 *
 * The allowlist accepts:
 *   - the app itself (same UID or same packageName, covers in-app
 *     MediaController binding and the case where a process runs under
 *     a shared UID),
 *   - the Android system UID (1000) — used by the system media
 *     notification and the AudioManager focus path,
 *   - a small explicit set of well-known media-controller packages
 *     (Android Auto / Assistant / Wear / Automotive). Adding a new
 *     trusted controller is an explicit, reviewable change here.
 *
 * The helper is pure (no Android imports) so it's unit-testable from
 * `androidUnitTest` without Robolectric. Caller passes the dynamic
 * pieces (`ownUid`, `ownPackageName`) explicitly.
 */
internal const val SYSTEM_UID: Int = 1000

// Order is informational — each entry corresponds to:
//   Android Auto, Google Assistant, Android Automotive OS, Wear OS bridge,
//   SystemUI (system media controls in the shade / quick settings).
internal val TRUSTED_MEDIA_CONTROLLER_PACKAGES: Set<String> =
    setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.automotive.media",
        "com.google.android.wearable.app",
        "com.android.systemui",
    )

internal fun isTrustedMediaController(
    packageName: String,
    uid: Int,
    ownUid: Int,
    ownPackageName: String,
): Boolean =
    uid == ownUid ||
        uid == SYSTEM_UID ||
        packageName == ownPackageName ||
        packageName in TRUSTED_MEDIA_CONTROLLER_PACKAGES
