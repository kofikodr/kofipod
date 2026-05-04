// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.kofipod.util.PendingHolder
import kotlinx.coroutines.CompletableDeferred
import org.koin.compose.koinInject

// CREATE_DOCUMENT MIME. We use `text/x-opml` rather than `application/xml` so SAF does
// not append `.xml` to the suggested filename — Android's MimeTypeMap maps
// `application/xml` → `xml`, which would turn `kofipod-subscriptions-YYYY-MM-DD.opml`
// into `…opml.xml`. `text/x-opml` is the de facto OPML MIME and is unregistered with
// MimeTypeMap, so the suggested filename is preserved verbatim.
private const val OPML_CREATE_MIME = "text/x-opml"

// OPEN_DOCUMENT filter list. `text/x-opml` first (matches what we wrote), plus the
// XML MIMEs other apps commonly write OPML as, plus `*/*` as a fallback for pickers
// that strip explicit types.
private val OPML_OPEN_MIMES = arrayOf(OPML_CREATE_MIME, "application/xml", "text/xml", "*/*")

@Composable
actual fun OpmlPickerHost() {
    val context = LocalContext.current
    val port = koinInject<OpmlFilePort>() as? AndroidOpmlFilePort ?: return

    val pendingImport = remember { PendingHolder<CompletableDeferred<ByteArray?>>() }
    val pendingExport = remember { PendingHolder<AndroidOpmlFilePort.ExportRequest>() }

    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingImport.take() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                deferred.complete(null)
                return@rememberLauncherForActivityResult
            }
            val bytes =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            if (bytes == null) {
                deferred.completeExceptionally(IllegalStateException("Couldn't read OPML file"))
            } else {
                deferred.complete(bytes)
            }
        }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OPML_CREATE_MIME)) { uri: Uri? ->
            val request = pendingExport.take() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                request.deferred.complete(false)
                return@rememberLauncherForActivityResult
            }
            val ok =
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(request.content.encodeToByteArray())
                    } ?: error("Couldn't open output stream")
                    true
                }.getOrElse {
                    request.deferred.completeExceptionally(it)
                    return@rememberLauncherForActivityResult
                }
            request.deferred.complete(ok)
        }

    LaunchedEffect(port, "opml-imports") {
        port.imports.collect { deferred ->
            pendingImport.set(deferred)
            runCatching { openLauncher.launch(OPML_OPEN_MIMES) }
                .onFailure {
                    pendingImport.take()
                    deferred.completeExceptionally(it)
                }
        }
    }
    LaunchedEffect(port, "opml-exports") {
        port.exports.collect { request ->
            pendingExport.set(request)
            runCatching { saveLauncher.launch(request.suggestedFilename) }
                .onFailure {
                    pendingExport.take()
                    request.deferred.completeExceptionally(it)
                }
        }
    }
}
