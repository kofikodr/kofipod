// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins [removeIfStillOurs]'s identity semantics. The helper guards
 * [AiSummaryRepository]'s `activeJobs` cleanup against a duplicate-Generate
 * race:
 *
 *   1. Job A launched for episode X — activeJobs[X] = A.
 *   2. User taps Generate again — Job B launched.
 *   3. activeJobs[X] = B (overwrites A's slot).
 *   4. Job A's `invokeOnCompletion` fires after B took the slot.
 *
 * If A's completion blindly removed activeJobs[X], it would drop B — the
 * still-in-flight job — leaving `cancel(X)` / `clearAll()` blind to a job
 * that's actually running and uploading audio to Gemini. The identity check
 * keeps the slot untouched when it no longer references this job.
 *
 * Test uses Any() as the value type because the production callers use
 * `kotlinx.coroutines.Job` references and identity is the contract; the
 * helper works for any reference-typed V.
 */
class RemoveIfStillOursTest {
    @Test
    fun removesEntry_whenSlotIdentityMatches() {
        val jobA = Any()
        val map: Map<String, Any> = mapOf("ep" to jobA)
        val result = removeIfStillOurs(map, "ep", jobA)
        // Pin both halves: removal happened AND the entry is gone. Plain
        // emptyMap() equality would also pass an `always returns emptyMap()`
        // bug; this combination wouldn't.
        assertEquals(false, result.containsKey("ep"), "Removed entry must not be in the result")
        assertEquals(emptyMap<String, Any>(), result)
    }

    @Test
    fun leavesEntry_whenSlotPointsToDifferentJob() {
        // The race we're guarding against: completion of A fires after B
        // overwrote the slot. Removing here would drop B's tracking entry
        // and leak the in-flight upload past cancel/clearAll.
        val jobA = Any()
        val jobB = Any()
        val map: Map<String, Any> = mapOf("ep" to jobB)
        val result = removeIfStillOurs(map, "ep", jobA)
        assertEquals(map, result, "Slot pointing to a different job must not be touched")
        assertSame(jobB, result["ep"], "Newer job must remain tracked")
    }

    @Test
    fun leavesMap_whenKeyAbsent() {
        // Either the slot was already cleaned (legitimate) or never existed.
        // Both branches are no-ops for the cleanup contract.
        //
        // Start from a non-empty map so an "always returns emptyMap()" bug
        // would visibly drop the sibling entry — the previous assertion was
        // vacuous against that mutation because the input was already empty.
        val jobA = Any()
        val jobSibling = Any()
        val map: Map<String, Any> = mapOf("other-episode" to jobSibling)
        val result = removeIfStillOurs(map, "ep", jobA)
        assertEquals(map, result, "Absent-key removal must leave the rest of the map intact")
    }

    @Test
    fun usesReferenceEquality_notStructuralEquality() {
        // Critical: ===, not ==. Two equal-by-equals values (say two
        // distinct Kotlin Jobs that happened to be equals()-equal — Job
        // doesn't override but defensive against future value-types)
        // must be treated as different so the per-launch identity is the
        // ground truth.
        val a = ValueObject(id = "shared")
        val b = ValueObject(id = "shared")
        check(a == b) { "Test premise: a == b structurally" }
        check(a !== b) { "Test premise: a !== b by identity" }

        val map: Map<String, ValueObject> = mapOf("ep" to b)
        val result = removeIfStillOurs(map, "ep", a)
        assertEquals(map, result, "Reference-different objects with equal structure must NOT trigger removal")
    }

    @Test
    fun otherKeysUnaffected_whenRemovalFires() {
        val jobA = Any()
        val jobOther = Any()
        val map: Map<String, Any> = mapOf("ep" to jobA, "other" to jobOther)
        val result = removeIfStillOurs(map, "ep", jobA)
        assertEquals(mapOf("other" to jobOther), result, "Sibling entries must remain intact")
    }

    @Test
    fun otherKeysUnaffected_whenIdentityCheckBlocksRemoval() {
        val jobA = Any()
        val jobB = Any()
        val jobOther = Any()
        val map: Map<String, Any> = mapOf("ep" to jobB, "other" to jobOther)
        val result = removeIfStillOurs(map, "ep", jobA)
        assertEquals(map, result)
    }

    private data class ValueObject(val id: String)
}
