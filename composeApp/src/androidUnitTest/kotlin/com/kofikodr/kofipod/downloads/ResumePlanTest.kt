// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pin the resume decision for every (existing-bytes, range-sent, status-code) shape
 * `DownloadService` can see. The crucial branch — `sentRangeRequest=true` + `200` —
 * is the kode-review HIGH finding: prior to this fix, the service treated any
 * `isSuccessful` response as a continuation, opened the file in append mode, and
 * concatenated the duplicate prefix bytes when a server ignored `Range` and sent the
 * full body. The pin here is `Overwrite`, not `Append`.
 */
class ResumePlanTest {
    @Test
    fun freshDownload_serverReturns200_overwrites() {
        assertEquals(
            ResumePlan.Overwrite,
            resumePlan(existingBytes = 0L, sentRangeRequest = false, responseCode = 200),
        )
    }

    @Test
    fun resume_serverReturns206_appendsFromExisting() {
        assertEquals(
            ResumePlan.Append(from = 1024L),
            resumePlan(existingBytes = 1024L, sentRangeRequest = true, responseCode = 206),
        )
    }

    @Test
    fun resume_serverReturns200_overwrites_notAppends() {
        // The bug. A server that ignores `Range: bytes=1024-` and returns the full
        // body with `200 OK` was previously appended onto the existing 1024 bytes,
        // producing a corrupt file. Must overwrite.
        assertEquals(
            ResumePlan.Overwrite,
            resumePlan(existingBytes = 1024L, sentRangeRequest = true, responseCode = 200),
        )
    }

    @Test
    fun resume_serverReturns416_fails() {
        // Out of scope for this fix but pin the path so a future recovery attempt
        // is a deliberate change, not an accidental one.
        assertEquals(
            ResumePlan.Fail(httpCode = 416),
            resumePlan(existingBytes = 1024L, sentRangeRequest = true, responseCode = 416),
        )
    }

    @Test
    fun freshDownload_serverReturns404_fails() {
        assertEquals(
            ResumePlan.Fail(httpCode = 404),
            resumePlan(existingBytes = 0L, sentRangeRequest = false, responseCode = 404),
        )
    }

    @Test
    fun freshDownload_serverReturns500_fails() {
        assertEquals(
            ResumePlan.Fail(httpCode = 500),
            resumePlan(existingBytes = 0L, sentRangeRequest = false, responseCode = 500),
        )
    }

    @Test
    fun unsolicited206_serverReturns206_withoutRangeSent_overwrites() {
        // Malformed server: returns 206 when we didn't ask for a range. We have no
        // way to know what offset it thinks it's sending from, so treat as a fresh
        // download.
        assertEquals(
            ResumePlan.Overwrite,
            resumePlan(existingBytes = 0L, sentRangeRequest = false, responseCode = 206),
        )
    }

    @Test
    fun resume_serverReturns403_failsRegardlessOfRangeFlag() {
        // Pins that the `else` branch is code-first, not range-flag-first. If the
        // `when` were restructured to gate on `sentRangeRequest` before checking
        // the code, this case could regress to a wrong branch silently.
        assertEquals(
            ResumePlan.Fail(httpCode = 403),
            resumePlan(existingBytes = 2048L, sentRangeRequest = true, responseCode = 403),
        )
    }

    @Test
    fun redirect301_fails() {
        // okhttp follows 3xx below this layer, so a 301 surviving up to `resumePlan`
        // means the redirect chain was exhausted or rejected. Treat as failure rather
        // than guess at the body shape.
        assertEquals(
            ResumePlan.Fail(httpCode = 301),
            resumePlan(existingBytes = 0L, sentRangeRequest = false, responseCode = 301),
        )
    }

    @Test
    fun responseCodeZero_fails() {
        // OkHttp can surface a `code = 0` (or other sentinel) on socket errors and
        // cancelled calls. Document the path: `Fail(0)` so the caller knows the
        // remote never sent a real status.
        assertEquals(
            ResumePlan.Fail(httpCode = 0),
            resumePlan(existingBytes = 1024L, sentRangeRequest = true, responseCode = 0),
        )
    }

    @Test
    fun sentRangeWithZeroExisting_serverReturns206_overwrites() {
        // Defensive: existingBytes=0 means nothing on disk to continue from, so
        // even a 206 must start at offset 0. (In practice the service won't ever
        // send Range with existing=0, but pinning the branch documents the rule.)
        assertEquals(
            ResumePlan.Overwrite,
            resumePlan(existingBytes = 0L, sentRangeRequest = true, responseCode = 206),
        )
    }
}
