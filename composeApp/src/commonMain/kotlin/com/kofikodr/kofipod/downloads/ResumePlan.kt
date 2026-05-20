// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

/**
 * What to do with the on-disk partial when the HTTP response comes back. Computed
 * from the bytes we already have on disk and the status code the server returned —
 * the two together determine whether the body the server is sending is a continuation
 * of what we have, the whole file, or something we should not write at all.
 *
 * The reason this lives in its own type rather than as inline branches in
 * `DownloadService.downloadWithResume`: the previous implementation conflated
 * "isSuccessful" (200..299) with "this is a continuation", which silently corrupted
 * the file when the server ignored our `Range` header and sent the full body. Pulling
 * the decision out makes the rule explicit and unit-testable without standing up
 * okhttp + a service container.
 */
internal sealed interface ResumePlan {
    /** Stream the body into the file from offset 0, truncating any partial bytes. */
    data object Overwrite : ResumePlan

    /** Stream the body onto the end of the existing partial. Only valid for 206. */
    data class Append(val from: Long) : ResumePlan

    /**
     * The server reported a hard failure. Don't touch the file; surface the code so
     * the caller can emit a Failed progress event. (For 416 we *could* try to recover
     * by treating the existing file as complete, but the kode-review finding scopes
     * this fix to the Range-ignored case; preserve the prior behaviour for non-2xx.)
     */
    data class Fail(val httpCode: Int) : ResumePlan
}

/**
 * Decide what to do with the partial file given how big it is and what the server
 * said. Pure — no IO, no state — so the rule can be pinned by tests that don't need
 * a network. Caller passes whether it sent a `Range` request so we know whether 200
 * means "fresh full-body download" (no resume in flight) or "server ignored our
 * Range and is re-sending the whole file" (must overwrite, not append).
 */
internal fun resumePlan(
    existingBytes: Long,
    sentRangeRequest: Boolean,
    responseCode: Int,
): ResumePlan =
    when {
        // 206 Partial Content is the only path that is safe to append. Server has
        // honoured our Range and is sending only the bytes we asked for.
        responseCode == 206 ->
            if (sentRangeRequest && existingBytes > 0) {
                ResumePlan.Append(from = existingBytes)
            } else {
                // 206 without a Range header is malformed; treat as a fresh body.
                ResumePlan.Overwrite
            }
        // 200 OK means the server is sending the whole file. If we asked for a
        // partial it has ignored our Range header (legacy CDNs, some object stores).
        // Either way the right thing to do is overwrite — never append, or we
        // duplicate the prefix bytes and produce a corrupt audio file.
        responseCode == 200 -> ResumePlan.Overwrite
        // Anything else (including 416 Range Not Satisfiable, which is technically
        // recoverable but out of scope here) is a failure.
        else -> ResumePlan.Fail(httpCode = responseCode)
    }
