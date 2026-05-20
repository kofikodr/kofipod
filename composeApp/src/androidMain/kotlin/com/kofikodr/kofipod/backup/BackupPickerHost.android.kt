// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import android.content.Intent
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

// OpenDocument MIME filter list. Custom MIME first (matches what we wrote), then
// `application/zip` for any provider that classifies our file by structure rather than
// extension, then `*/*` as a fallback for pickers that strip explicit MIME types.
private val RESTORE_OPEN_MIMES = arrayOf(BACKUP_MIME, "application/zip", "*/*")

@Composable
actual fun BackupPickerHost() {
    val context = LocalContext.current
    val port = koinInject<BackupFilePort>() as? AndroidBackupFilePort ?: return
    val scope = rememberCoroutineScope()

    val pendingFolderPick =
        remember { PendingHolder<CompletableDeferred<String?>>() }
    val pendingRestorePick =
        remember { PendingHolder<CompletableDeferred<ByteArray?>>() }

    val openTreeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val deferred = pendingFolderPick.take() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                deferred.complete(null)
                return@rememberLauncherForActivityResult
            }
            // Persist the grant so the URI is usable across process restart and reboot.
            // Without this the permission only survives the lifetime of this Activity.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val ok =
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }.isSuccess
            if (!ok) {
                deferred.completeExceptionally(
                    IllegalStateException("Couldn't keep access to that folder. Try again."),
                )
            } else {
                deferred.complete(uri.toString())
            }
        }

    val openDocLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val deferred = pendingRestorePick.take() ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                deferred.complete(null)
                return@rememberLauncherForActivityResult
            }
            // Move the IO off Main. SAF can resolve to a cloud-backed provider
            // (Drive / Dropbox / etc) where the read does real network work — a
            // synchronous Main-thread read ANRs for non-trivial files. The size
            // cap also lives in `readCappedBackupFromUri` so a hostile or
            // misconfigured provider can't OOM the heap.
            scope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            readCappedBackupFromUri(context.contentResolver, uri)
                        }
                    when (result) {
                        is BackupRestoreReadResult.Ok -> deferred.complete(result.bytes)
                        BackupRestoreReadResult.Unreadable ->
                            deferred.completeExceptionally(IllegalStateException("Couldn't read backup file"))
                        is BackupRestoreReadResult.TooLarge ->
                            deferred.completeExceptionally(
                                IllegalStateException("Backup file is too large (${result.cap / (1024L * 1024L)} MB cap)"),
                            )
                    }
                } catch (cancel: CancellationException) {
                    // Composition was torn down mid-read (e.g. Activity recreated
                    // while picker was settling). Surface the cancel to the caller
                    // immediately so the controller's single-flight guard clears,
                    // rather than letting `pickAndReadBackup`'s 5-minute timeout fire.
                    deferred.completeExceptionally(cancel)
                    throw cancel
                }
            }
        }

    LaunchedEffect(port, "backup-folder-picks") {
        port.folderPicks.collect { request ->
            pendingFolderPick.set(request.deferred)
            // OpenDocumentTree takes an optional initial-URI; pass null to let SAF pick a
            // sensible default (last visited / Recents).
            runCatching { openTreeLauncher.launch(null) }
                .onFailure {
                    pendingFolderPick.take()
                    request.deferred.completeExceptionally(it)
                }
        }
    }
    LaunchedEffect(port, "backup-restore-picks") {
        port.restorePicks.collect { request ->
            pendingRestorePick.set(request.deferred)
            runCatching { openDocLauncher.launch(RESTORE_OPEN_MIMES) }
                .onFailure {
                    pendingRestorePick.take()
                    request.deferred.completeExceptionally(it)
                }
        }
    }
}
