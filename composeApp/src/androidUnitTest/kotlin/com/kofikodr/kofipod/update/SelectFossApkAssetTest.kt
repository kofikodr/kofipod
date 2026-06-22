// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectFossApkAssetTest {
    private fun asset(
        name: String,
        url: String = "https://example.test/$name",
    ): GithubAsset = GithubAsset(name = name, browserDownloadUrl = url, size = 1L, contentType = "application/vnd.android.package-archive")

    @Test
    fun `returns the foss-tagged APK when both play and foss APKs are present`() {
        val playApk = asset("kofipod-play-1.3.3-18-release.apk")
        val fossApk = asset("kofipod-foss-1.3.3-foss-18-release.apk")

        val picked = selectFossApkAsset(listOf(playApk, fossApk))

        assertEquals(fossApk, picked)
    }

    @Test
    fun `prefers foss APK even when buried deep in the asset list`() {
        // Foss at index 3 — defeats a broken implementation that always
        // returns the first APK in the filtered list.
        val mappingTxt = asset("mapping.txt")
        val sourceZip = asset("source-code.zip")
        val playApk = asset("kofipod-play-1.3.3-18-release.apk")
        val fossApk = asset("kofipod-foss-1.3.3-foss-18-release.apk")
        val aab = asset("kofipod-play-1.3.3-18-release.aab")

        val picked = selectFossApkAsset(listOf(mappingTxt, sourceZip, playApk, fossApk, aab))

        assertEquals(fossApk, picked)
    }

    @Test
    fun `returns null for a non-foss-tagged single APK instead of offering it`() {
        // Issue #30: a generically-named APK (e.g. a pre-flavor-split or
        // play-flavor build) must NOT be offered to a foss install — it can't
        // upgrade it (different applicationId). Pre-fix this returned the APK
        // via the now-removed fallback; it must now report "no compatible asset".
        val legacyApk = asset("kofipod-1.2.4-14-release.apk")

        assertNull(selectFossApkAsset(listOf(legacyApk)))
    }

    @Test
    fun `returns null when a release ships only non-foss APKs`() {
        // No foss-tagged asset present among several non-foss APKs → nothing
        // compatible to offer, rather than picking an arbitrary first .apk.
        val notes = asset("release-notes.txt")
        val playApk = asset("kofipod-play-1.2.4-14-release.apk")
        val altApk = asset("kofipod-alt-1.2.4-14-release.apk")

        assertNull(selectFossApkAsset(listOf(notes, playApk, altApk)))
    }

    @Test
    fun `does not mistake a non-foss APK whose name happens to contain '-foss-' inside`() {
        // Adversarial: the picker anchors on the `kofipod-foss-` PREFIX, not
        // a substring `-foss-`. A hypothetical compat/addon build named
        // `kofipod-play-foss-compat-*.apk` must NOT be classified as foss.
        val canonicalFoss = asset("kofipod-foss-1.3.3-foss-18-release.apk")
        val confusinglyNamedPlay = asset("kofipod-play-foss-compat-1.3.3-18-release.apk")

        // With both present, canonical foss wins regardless of order.
        assertEquals(
            canonicalFoss,
            selectFossApkAsset(listOf(confusinglyNamedPlay, canonicalFoss)),
        )

        // With only non-foss APKs (a canonical play build and the confusingly
        // named one), neither matches the `kofipod-foss-` prefix, so nothing is
        // offered — no fallback to an arbitrary play APK (issue #30).
        val canonicalPlay = asset("kofipod-play-1.3.3-18-release.apk")
        assertNull(selectFossApkAsset(listOf(canonicalPlay, confusinglyNamedPlay)))
    }

    @Test
    fun `returns null when no APK assets are present`() {
        val aab = asset("kofipod-play-1.3.3-18-release.aab")
        val zip = asset("source-code.zip")

        val picked = selectFossApkAsset(listOf(aab, zip))

        assertNull(picked)
    }

    @Test
    fun `returns null for empty asset list`() {
        assertNull(selectFossApkAsset(emptyList()))
    }

    @Test
    fun `ignores non-APK files even when they contain '-foss-' in the name`() {
        // A future foss-mapping.txt or foss-checksums.sha256 attachment must not
        // be mistaken for an installable APK.
        val fossMapping = asset("kofipod-foss-mapping.txt")
        val fossApk = asset("kofipod-foss-1.3.3-foss-18-release.apk")

        // Foss APK still wins.
        assertEquals(fossApk, selectFossApkAsset(listOf(fossMapping, fossApk)))

        // With only the non-APK foss-named file, nothing installable is picked.
        assertNull(selectFossApkAsset(listOf(fossMapping)))
    }

    @Test
    fun `matches APK extension case-insensitively on the foss-preference path`() {
        val upperCaseFossApk = asset("kofipod-foss-1.3.3-foss-18-release.APK")
        assertEquals(upperCaseFossApk, selectFossApkAsset(listOf(upperCaseFossApk)))
    }

    @Test
    fun `matches the foss prefix case-insensitively`() {
        // The `kofipod-foss-` prefix match is case-insensitive too, so an
        // upper/mixed-case foss asset name is still recognised.
        val mixedCaseFossApk = asset("KOFIPOD-FOSS-1.3.3-foss-18-release.apk")
        assertEquals(mixedCaseFossApk, selectFossApkAsset(listOf(mixedCaseFossApk)))
    }
}
