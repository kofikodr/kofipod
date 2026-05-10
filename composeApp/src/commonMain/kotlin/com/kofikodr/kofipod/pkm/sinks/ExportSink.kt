// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.PkmExportRequest

sealed interface ExportSinkResult {
    data class Success(val externalId: String?) : ExportSinkResult

    /** Network-class failure — eligible for retry by PkmExportWorker. */
    data class TransientFailure(val message: String) : ExportSinkResult

    /** Permanent failure — never retry (e.g. missing connection, permission revoked). */
    data class PermanentFailure(val message: String) : ExportSinkResult
}

interface ExportSink {
    suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult
}
