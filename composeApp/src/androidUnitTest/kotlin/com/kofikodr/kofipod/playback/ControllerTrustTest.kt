// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [isTrustedMediaController]'s allowlist. The function is the gate that
 * decides whether an arbitrary installed app can bind as a `MediaBrowser`
 * client to [KofipodPlaybackService] and read out the user's library tree
 * + playback state. A regression that flips the default to "allow" turns
 * every Kofipod install into a snoopable library; a regression that flips
 * to "deny" breaks Android Auto / system media notification integration.
 */
class ControllerTrustTest {
    private val ownUid = 10123
    private val ownPackage = "com.kofikodr.kofipod"

    @Test
    fun acceptsSelfByMatchingUid() {
        // In-app MediaController binding from the same process — most common
        // case during normal app use.
        assertTrue(
            isTrustedMediaController(
                packageName = ownPackage,
                uid = ownUid,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsSelfByMatchingUidOnly_packageDoesNotMatchAnyAllowlist() {
        // Isolates the `uid == ownUid` branch: if it were removed, the test
        // would fail because the package is not in any allowlist and is not
        // ownPackageName. Pins the UID-identity path independently.
        assertTrue(
            isTrustedMediaController(
                packageName = "com.unrelated.process.under.same.uid",
                uid = ownUid,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsSelfByMatchingPackageName_evenWhenUidDiffers() {
        // Multi-process scenario or KMP-debug attach with a different UID —
        // packageName match is still us.
        //
        // Defence-in-depth note: on rooted/development devices, another app
        // CAN run under a different UID while claiming our packageName (via
        // sharedUserId tricks). The package manager normally enforces the
        // package↔UID binding, so this widening is acceptable for stock
        // Android; if a tighter check is ever needed, combine `uid == ownUid`
        // AND `packageName == ownPackageName` rather than OR-ing them.
        assertTrue(
            isTrustedMediaController(
                packageName = ownPackage,
                uid = 99999,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsSelfForFossFlavorPackage() {
        // The foss flavor's applicationId is `com.kofikodr.kofipod.foss`. The
        // service uses Service.packageName at runtime so the comparison is
        // already flavor-aware; pin the foss-flavor binding to lock that the
        // same-package check isn't accidentally hard-coded to the play id.
        val fossPackage = "com.kofikodr.kofipod.foss"
        assertTrue(
            isTrustedMediaController(
                packageName = fossPackage,
                uid = ownUid,
                ownUid = ownUid,
                ownPackageName = fossPackage,
            ),
        )
    }

    @Test
    fun acceptsSystemUid_isolatedFromPackageAllowlist() {
        // System UID (1000) drives the system media notification, AudioManager
        // focus path, and several MediaSession internals — without this entry,
        // playback notifications stop working. The packageName here is NOT in
        // TRUSTED_MEDIA_CONTROLLER_PACKAGES (`com.android.settings` is system
        // but not in the media-controller list), so if `uid == SYSTEM_UID` were
        // removed, only this test would fail — pinning the UID branch alone.
        assertTrue(
            isTrustedMediaController(
                packageName = "com.android.settings",
                uid = SYSTEM_UID,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsAndroidAutoPackage() {
        assertTrue(
            isTrustedMediaController(
                packageName = "com.google.android.projection.gearhead",
                uid = 10456,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsGoogleAssistantPackage() {
        assertTrue(
            isTrustedMediaController(
                packageName = "com.google.android.googlequicksearchbox",
                uid = 10789,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsWearOsBridgePackage() {
        // Wear OS sends transport-control commands over the bridge service.
        // Allowlisted so podcast-control-from-watch keeps working.
        assertTrue(
            isTrustedMediaController(
                packageName = "com.google.android.wearable.app",
                uid = 10888,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsAutomotiveOsMediaPackage() {
        assertTrue(
            isTrustedMediaController(
                packageName = "com.google.android.apps.automotive.media",
                uid = 10999,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun rejectsArbitraryInstalledApp() {
        // The exact attack scenario from the audit: another app installed on
        // the device tries to bind as a media browser and walk the library.
        // Must be rejected.
        assertEquals(
            false,
            isTrustedMediaController(
                packageName = "com.malicious.snooper",
                uid = 10333,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsTrustedPackage_relyOnAndroidsPackageToUidBinding() {
        // Android verifies the bound UID matches the declared packageName for
        // installed apps — a malicious app cannot spoof `packageName` in the
        // MediaBrowser handshake. So packageName-only allowlist is sufficient.
        // Pin that the function accepts the packageName path without requiring
        // the UID to also match a known-good value.
        //
        // Renamed from `rejectsSpoofedPackageWithUntrustedUid` (the original
        // name said "rejects" but the body asserts `true` — a security-test
        // naming inversion that would mislead future reviewers).
        assertTrue(
            isTrustedMediaController(
                packageName = "com.google.android.projection.gearhead",
                uid = 99999,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun rejectsBlankPackageName_evenWithUnknownUid() {
        // Defensive: ControllerInfo's contract says packageName is
        // non-null but Media3 has historically had bugs where empty strings
        // slipped through. An empty packageName must not match any allowlist
        // entry (set membership is exact-match, empty string is not in the
        // set).
        assertEquals(
            false,
            isTrustedMediaController(
                packageName = "",
                uid = 10444,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun rejectsTrustedPackageWithPrefixSubstitution() {
        // Pin exact-match: `"com.google.android.projection.gearhead.evil"`
        // (a malicious app that names itself with a trusted-prefix) must NOT
        // be accepted. Set membership is exact; assert it stays that way.
        assertEquals(
            false,
            isTrustedMediaController(
                packageName = "com.google.android.projection.gearhead.evil",
                uid = 10555,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun rejectsTrustedPackageSuffixSubstitution() {
        // Inverse of the previous: `"evil.com.google.android.projection.gearhead"`
        // also must NOT match. Pin both prefix-direction tampers.
        assertEquals(
            false,
            isTrustedMediaController(
                packageName = "evil.com.google.android.projection.gearhead",
                uid = 10666,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun rejectsOwnPackagePrefixSubstitution() {
        // Defence-in-depth for the ownPackage path: a malicious app that
        // installs as "com.kofikodr.kofipod.evil" must not be allowed via
        // the packageName equality check. Pin exact-match.
        assertEquals(
            false,
            isTrustedMediaController(
                packageName = "com.kofikodr.kofipod.evil",
                uid = 10777,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }

    @Test
    fun acceptsSystemUiByPackage_evenIfUidIsNotSystemUid() {
        // SystemUI is also in the packageName allowlist (some Android
        // versions run it under a non-1000 UID). Pin the packageName route.
        assertTrue(
            isTrustedMediaController(
                packageName = "com.android.systemui",
                uid = 10101,
                ownUid = ownUid,
                ownPackageName = ownPackage,
            ),
        )
    }
}
