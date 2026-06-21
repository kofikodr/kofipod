// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PodcastIndexValidatorTest {
    @Test
    fun validProbe_returnsValid() =
        runTest {
            val v = DefaultPodcastIndexValidator(probe = { /* success */ })
            assertEquals(PodcastIndexValidation.Valid, v.validate(PodcastIndexCreds("k", "s")))
        }

    @Test
    fun authFailure_returnsInvalid() =
        runTest {
            val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("HTTP 401 Unauthorized") })
            assertEquals(PodcastIndexValidation.Invalid, v.validate(PodcastIndexCreds("k", "s")))
        }

    @Test
    fun forbidden_returnsInvalid() =
        runTest {
            val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("response 403") })
            assertEquals(PodcastIndexValidation.Invalid, v.validate(PodcastIndexCreds("k", "s")))
        }

    @Test
    fun otherFailure_returnsNetworkError() =
        runTest {
            val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("Unable to resolve host") })
            assertEquals(PodcastIndexValidation.NetworkError, v.validate(PodcastIndexCreds("k", "s")))
        }

    @Test
    fun cancellation_propagates() =
        runTest {
            val v = DefaultPodcastIndexValidator(probe = { throw CancellationException("cancelled") })
            assertFailsWith<CancellationException> { v.validate(PodcastIndexCreds("k", "s")) }
        }

    @Test
    fun classify_matchesStatusInCauseChain() {
        assertEquals(
            PodcastIndexValidation.Invalid,
            classifyPodcastIndexFailure(RuntimeException(IllegalStateException("status=401"))),
        )
        assertEquals(
            PodcastIndexValidation.NetworkError,
            classifyPodcastIndexFailure(RuntimeException("timeout")),
        )
    }
}
