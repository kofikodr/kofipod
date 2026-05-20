// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kofikodr.kofipod.util.PendingHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()

    val pendingImport = remember { PendingHolder<CompletableDeferred<ByteArray?>>() }
    val pendingExport = remember { PendingHolder<AndroidOpmlFilePort.ExportRequest>() }

    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingImport.take() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                deferred.complete(null)
                return@rememberLauncherForActivityResult
            }
            // Move the IO off Main. The SAF picker can return a URI backed by a
            // remote provider (Drive / Dropbox / etc) where the read does real
            // network work — a synchronous Main-thread read ANRs for large files.
            // The size cap also lives in `readCappedFromUri` so a hostile or
            // misconfigured provider can't OOM the heap.
            scope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            readCappedFromUri(context.contentResolver, uri)
                        }
                    when (result) {
                        is OpmlReadResult.Ok -> deferred.complete(result.bytes)
                        OpmlReadResult.Unreadable ->
                            deferred.completeExceptionally(IllegalStateException("Couldn't read OPML file"))
                        is OpmlReadResult.TooLarge ->
                            deferred.completeExceptionally(
                                IllegalStateException("OPML file is too large (${result.cap / (1024L * 1024L)} MB cap)"),
                            )
                    }
                } catch (cancel: CancellationException) {
                    // Composition was torn down mid-read (e.g. Activity recreated
                    // while picker was settling). Surface the cancel to the caller
                    // immediately so the controller's single-flight guard clears,
                    // rather than letting `pickImport`'s 5-minute timeout fire.
                    deferred.completeExceptionally(cancel)
                    throw cancel
                }
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
