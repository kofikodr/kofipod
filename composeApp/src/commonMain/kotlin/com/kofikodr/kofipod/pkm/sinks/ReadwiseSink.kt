// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.pkm.connections.OAuthTokenVault
import com.kofikodr.kofipod.pkm.connections.PkmConnection

class ReadwiseSink(
    private val client: ReadwiseClient,
    private val vault: OAuthTokenVault,
    private val connectionLoader: suspend () -> PkmConnection?,
) : ExportSink {
    override suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult {
        val conn =
            connectionLoader()
                ?: return ExportSinkResult.PermanentFailure("Readwise not connected")
        val tokenRef =
            conn.tokenRef
                ?: return ExportSinkResult.PermanentFailure("Readwise tokenRef missing")
        val token =
            vault.get(tokenRef)
                ?: return ExportSinkResult.PermanentFailure("Readwise token missing in vault")

        val frontmatter = document.frontmatter.toMap()
        val title = frontmatter["episode"] ?: "Untitled episode"
        val author = frontmatter["podcast"]
        val sourceUrl =
            frontmatter["episodeUrl"]
                ?: return ExportSinkResult.PermanentFailure("Missing episodeUrl")
        val kofipodId =
            frontmatter["kofipodId"]
                ?: return ExportSinkResult.PermanentFailure("Missing kofipodId")

        return if (priorExternalId != null) {
            val id =
                priorExternalId.toLongOrNull()
                    ?: return ExportSinkResult.PermanentFailure("Invalid Readwise externalId")
            client.updateHighlight(
                token,
                id,
                ReadwiseUpdateRequest(text = document.body, note = "kofipodId:$kofipodId"),
            ).fold(
                onSuccess = { ExportSinkResult.Success(externalId = priorExternalId) },
                onFailure = { classifyFailure(it) },
            )
        } else {
            client.createHighlight(
                token,
                ReadwiseCreateRequest(
                    highlights =
                        listOf(
                            ReadwiseHighlightCreate(
                                text = document.body,
                                title = title,
                                author = author,
                                sourceUrl = sourceUrl,
                                note = "kofipodId:$kofipodId",
                            ),
                        ),
                ),
            ).fold(
                onSuccess = { ExportSinkResult.Success(externalId = it.toString()) },
                onFailure = { classifyFailure(it) },
            )
        }
    }

    /**
     * Map a Readwise call failure to the right [ExportSinkResult]. Auth/permission
     * failures (401/403) and other non-retryable 4xx become [PermanentFailure] so
     * the coordinator stops re-enqueuing the export — a revoked token would
     * otherwise loop through the retry worker forever. Network errors, rate-limits,
     * timeouts, and 5xx stay [TransientFailure] so genuine blips still retry.
     */
    private fun classifyFailure(t: Throwable): ExportSinkResult =
        when {
            t is ReadwiseHttpException && t.isAuthFailure ->
                ExportSinkResult.PermanentFailure(
                    "Readwise rejected your token (HTTP ${t.status}). " +
                        "Reconnect Readwise in Settings → Connections.",
                )
            t is ReadwiseHttpException && !t.isTransient ->
                ExportSinkResult.PermanentFailure("Readwise rejected the request (HTTP ${t.status}).")
            else ->
                // Anything without an HTTP status — socket/IO errors, timeouts, or an
                // unparseable response from a proxy/CDN during an outage — is treated as
                // transient on purpose: these self-heal, and the worker's exponential
                // backoff bounds the cost. We'd rather retry than silently drop an export.
                ExportSinkResult.TransientFailure(t.message ?: "Readwise request failed")
        }
}
