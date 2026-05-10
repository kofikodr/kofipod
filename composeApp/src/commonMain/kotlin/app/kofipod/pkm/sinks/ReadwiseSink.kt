// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import app.kofipod.pkm.MarkdownDocument
import app.kofipod.pkm.PkmExportRequest
import app.kofipod.pkm.connections.OAuthTokenVault
import app.kofipod.pkm.connections.PkmConnection

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
                onFailure = { ExportSinkResult.TransientFailure(it.message ?: "Readwise PATCH failed") },
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
                onFailure = { ExportSinkResult.TransientFailure(it.message ?: "Readwise POST failed") },
            )
        }
    }
}
