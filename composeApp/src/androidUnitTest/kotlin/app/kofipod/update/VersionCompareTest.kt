// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `compareSemver` is the single point that decides "is the GitHub release newer than the
 * installed version?". A regression here is silently catastrophic — debug builds would
 * either perpetually flag themselves as outdated, or stable builds would never see updates.
 * These tests lock in the contract documented on the function.
 */
class VersionCompareTest {
    @Test
    fun equalVersions_returnZero() {
        assertEquals(0, compareSemver("1.1.2", "1.1.2"))
        assertEquals(0, compareSemver("0.0.1", "0.0.1"))
    }

    @Test
    fun ordersByMajorMinorPatchNumerically() {
        assertNegative(compareSemver("1.1.2", "1.2.0"), "minor wins")
        assertNegative(compareSemver("1.1.2", "2.0.0"), "major wins")
        assertNegative(compareSemver("1.1.2", "1.1.3"), "patch wins")
        assertPositive(compareSemver("1.10.0", "1.9.9"), "10 > 9 numerically — must not lex-compare")
    }

    @Test
    fun stripsLeadingV() {
        assertEquals(0, compareSemver("v1.1.2", "1.1.2"))
        assertEquals(0, compareSemver("V1.1.2", "1.1.2"))
        assertNegative(compareSemver("v1.1.2", "v1.1.3"))
    }

    @Test
    fun ignoresSuffixAfterDash_so_debugBuildsDoNotPerpetuallyFlagThemselves() {
        // Hardcoded invariant: a debug build at 1.1.2-debug must compare as equal to a
        // stable release tagged v1.1.2, otherwise the update banner would never go away
        // on dev devices once any release of the same version had shipped.
        assertEquals(0, compareSemver("1.1.2-debug", "1.1.2"))
        assertEquals(0, compareSemver("1.1.2-debug", "v1.1.2"))
        assertEquals(0, compareSemver("1.1.2-rc1", "1.1.2"))
        // Suffix doesn't override the numeric comparison.
        assertNegative(compareSemver("1.1.2-debug", "1.1.3"))
    }

    @Test
    fun missingComponentsTreatedAsZero() {
        assertEquals(0, compareSemver("1.1", "1.1.0"))
        assertEquals(0, compareSemver("1", "1.0.0"))
        assertNegative(compareSemver("1.1", "1.1.1"), "1.1.0 < 1.1.1")
    }

    @Test
    fun handlesEmptyAndDegenerateInputs() {
        // Empty string degrades to "0", so it compares equal to any all-zero version.
        assertEquals(0, compareSemver("", "0.0.0"))
        assertEquals(0, compareSemver("v", ""))
        assertEquals(0, compareSemver("v", "0.0.0"))
        // And is strictly less than any nonzero release.
        assertNegative(compareSemver("", "1.0.0"))
    }

    @Test
    fun isAntisymmetricAndTransitive_smokeTest() {
        // Light sanity check that swapping inputs negates the sign and that ordering chains.
        val a = "1.0.0"
        val b = "1.0.1"
        val c = "1.1.0"
        assertNegative(compareSemver(a, b))
        assertPositive(compareSemver(b, a))
        assertNegative(compareSemver(b, c))
        assertNegative(compareSemver(a, c))
        assertPositive(compareSemver(c, b))
        assertPositive(compareSemver(c, a))
    }

    private fun assertNegative(
        value: Int,
        hint: String = "",
    ) {
        assertTrue(value < 0, "expected negative, got $value${if (hint.isNotEmpty()) " ($hint)" else ""}")
    }

    private fun assertPositive(
        value: Int,
        hint: String = "",
    ) {
        assertTrue(value > 0, "expected positive, got $value${if (hint.isNotEmpty()) " ($hint)" else ""}")
    }
}
