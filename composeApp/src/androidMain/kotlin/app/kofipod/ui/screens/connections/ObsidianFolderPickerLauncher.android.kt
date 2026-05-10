// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.connections

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberObsidianFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted =
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (granted) onPicked(uri.toString())
        }
    return { launcher.launch(null) }
}
