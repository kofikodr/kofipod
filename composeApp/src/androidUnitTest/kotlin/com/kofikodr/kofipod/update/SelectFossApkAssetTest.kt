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
    fun `falls back to first APK when no foss-tagged asset is present`() {
        // Pre-flavor-split release shape — single APK, no flavor in the name.
        val legacyApk = asset("kofipod-1.2.4-14-release.apk")

        val picked = selectFossApkAsset(listOf(legacyApk))

        assertEquals(legacyApk, picked)
    }

    @Test
    fun `fallback path picks first APK from a multi-APK non-foss release`() {
        // Documents fallback semantics — when no foss-tagged APK is present
        // and multiple non-foss APKs exist, the first .apk wins. Distinguishes
        // "correct filter+fallback" from "always return assets[0]" (which
        // would pick the .txt below).
        val notes = asset("release-notes.txt")
        val firstApk = asset("kofipod-1.2.4-14-release.apk")
        val secondApk = asset("kofipod-alt-1.2.4-14-release.apk")

        val picked = selectFossApkAsset(listOf(notes, firstApk, secondApk))

        assertEquals(firstApk, picked)
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

        // With only the confusingly-named play APK, the picker MUST NOT treat
        // it as foss. It falls through to the fallback branch (first .apk)
        // — that's the play APK here, but that's the fallback's job, not the
        // foss-preference branch. The point is: it didn't match the foss
        // predicate.
        val canonicalPlay = asset("kofipod-play-1.3.3-18-release.apk")
        // Both confusinglyNamedPlay and canonicalPlay are non-foss; foss
        // predicate misses both; fallback picks the first .apk in the list.
        assertEquals(
            canonicalPlay,
            selectFossApkAsset(listOf(canonicalPlay, confusinglyNamedPlay)),
        )
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
    fun `matches APK extension case-insensitively on the fallback path`() {
        // Uppercase extension on a legacy (non-foss) APK must still be picked
        // by the fallback branch — covers a regression where the fallback
        // could lose its case-insensitive filter.
        val upperCaseLegacyApk = asset("kofipod-1.2.4-14-release.APK")
        assertEquals(upperCaseLegacyApk, selectFossApkAsset(listOf(upperCaseLegacyApk)))
    }
}
