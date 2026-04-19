// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.drive

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Minimal Google Drive v3 client scoped to the caller's `appDataFolder`.
 *
 * The appDataFolder is a hidden per-app folder that doesn't count against the
 * user's Drive quota and is invisible in the normal Drive UI — ideal for a
 * private library backup blob.
 *
 * The [tokenProvider] is called before each request so token refresh logic
 * can be layered on transparently.
 */
class DriveClient(
    private val http: HttpClient,
    private val tokenProvider: suspend () -> String,
) {

    /**
     * Looks up a file by exact [name] inside `appDataFolder`.
     * Returns null if no file with that name exists.
     */
    suspend fun findAppDataFile(name: String): DriveFileRef? {
        val response = http.get("$DRIVE_BASE/files") {
            bearer()
            parameter("spaces", "appDataFolder")
            parameter("q", "name = '${escapeQuery(name)}' and trashed = false")
            parameter("fields", "files(id,name,modifiedTime,size)")
        }
        if (!response.status.isSuccess()) throw DriveException.from(response)
        val list: DriveFileList = response.body()
        return list.files.firstOrNull()
    }

    /** Downloads the raw text contents of a file. */
    suspend fun downloadText(fileId: String): String {
        val response = http.get("$DRIVE_BASE/files/$fileId") {
            bearer()
            parameter("alt", "media")
        }
        if (!response.status.isSuccess()) throw DriveException.from(response)
        return response.bodyAsText()
    }

    /**
     * Creates or updates a text file in `appDataFolder`.
     * When [existingFileId] is non-null, the file is PATCHed; otherwise a new
     * file is POSTed and placed under `appDataFolder`.
     */
    suspend fun upload(
        name: String,
        content: String,
        contentMimeType: String = "application/json",
        existingFileId: String? = null,
    ): DriveFileRef {
        val boundary = "kofipod_${kotlin.random.Random.nextLong().toString(16)}"
        val metadata = if (existingFileId == null) {
            // On create, parents must be set to appDataFolder so the file lands
            // in the hidden per-app space. On update, parents can't be reassigned
            // via multipart — the Drive API ignores / rejects it.
            """{"name":${quoteJson(name)},"parents":["appDataFolder"]}"""
        } else {
            """{"name":${quoteJson(name)}}"""
        }
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(contentMimeType).append("; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val response: HttpResponse = if (existingFileId == null) {
            http.post("$UPLOAD_BASE/files") {
                bearer()
                parameter("uploadType", "multipart")
                parameter("fields", "id,name,modifiedTime,size")
                contentType(ContentType.parse("multipart/related; boundary=$boundary"))
                setBody(body)
            }
        } else {
            http.patch("$UPLOAD_BASE/files/$existingFileId") {
                bearer()
                parameter("uploadType", "multipart")
                parameter("fields", "id,name,modifiedTime,size")
                contentType(ContentType.parse("multipart/related; boundary=$boundary"))
                setBody(body)
            }
        }
        if (!response.status.isSuccess()) throw DriveException.from(response)
        return response.body()
    }

    /** Permanently deletes a file. Used during sign-out cleanup. */
    suspend fun delete(fileId: String) {
        val response = http.delete("$DRIVE_BASE/files/$fileId") { bearer() }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NotFound) {
            throw DriveException.from(response)
        }
    }

    private suspend fun HttpRequestBuilder.bearer() {
        header(HttpHeaders.Authorization, "Bearer ${tokenProvider()}")
    }

    companion object {
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

        /** Fixed blob name — changing this invalidates all existing backups. */
        const val BACKUP_FILE_NAME = "kofipod-backup-v1.json"

        private val jsonEscaper = Json { encodeDefaults = true }

        private fun escapeQuery(s: String): String = s.replace("'", "\\'")
        private fun quoteJson(s: String): String =
            jsonEscaper.encodeToString(String.serializer(), s)
    }
}

@Serializable
data class DriveFileRef(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
    val size: String? = null,
)

@Serializable
private data class DriveFileList(val files: List<DriveFileRef> = emptyList())

sealed class DriveException(message: String) : Exception(message) {
    class Http(val status: Int, val detail: String) : DriveException("Drive HTTP $status: $detail")
    class Unauthorized(val detail: String) : DriveException("Drive unauthorized: $detail")

    companion object {
        suspend fun from(response: HttpResponse): DriveException {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
            return if (response.status == HttpStatusCode.Unauthorized) {
                Unauthorized(detail)
            } else {
                Http(response.status.value, detail)
            }
        }
    }
}
