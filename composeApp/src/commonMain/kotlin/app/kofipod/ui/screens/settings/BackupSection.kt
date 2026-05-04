// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kofipod.backup.BackupAction
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SettingRow
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Four-row Backup section + restore confirmation dialog. Mirrors the OPML import/export
 * pair in [SettingsScreen]: each row is a `SettingRow`, the action's progress is shown
 * inline as the subtitle, errors clear via tap-anywhere-else (handled in the controller's
 * `dismissError`).
 *
 * The static "About automatic system backup" explainer is rendered only when the user
 * has NOT picked a SAF folder. Once a folder is configured, the SAF backup is the
 * authoritative path and the Auto Backup explainer would only confuse the mental model.
 */
@Composable
internal fun BackupSection(
    state: SettingsUiState,
    onChooseFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestoreConfirm: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val backupAction = state.backupAction
    val hasFolder = !state.backupFolderUri.isNullOrEmpty()
    val idle = backupAction is BackupAction.Idle || backupAction is BackupAction.Error
    val errorMessage = (backupAction as? BackupAction.Error)?.message

    SettingRow(
        icon = KPIconName.Folder,
        title = "Backup folder",
        subtitle =
            when {
                !hasFolder -> "Tap to pick a folder in Drive, Dropbox, Files, or any storage app"
                state.backupFolderName != null -> state.backupFolderName
                else -> "Folder set"
            },
        onClick = if (idle) onChooseFolder else null,
        trailing = {
            KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
        },
    )
    Spacer(Modifier.height(8.dp))

    SettingRow(
        icon = KPIconName.Clock,
        title = "Last backup",
        subtitle = lastBackupSubtitle(state.lastBackupAtMs, hasFolder = hasFolder),
    )
    Spacer(Modifier.height(8.dp))

    SettingRow(
        icon = KPIconName.Download,
        title = "Back up now",
        subtitle =
            when {
                backupAction is BackupAction.BackingUp -> "Backing up…"
                errorMessage != null -> errorMessage
                !hasFolder -> "Pick a folder above to enable"
                else -> "Write a backup file to your folder"
            },
        // Disable while another op is in flight (BackingUp / Restoring) and when no folder.
        onClick = if (idle && hasFolder) onBackupNow else null,
        trailing = {
            KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
        },
    )
    Spacer(Modifier.height(8.dp))

    SettingRow(
        icon = KPIconName.Library,
        title = "Restore from backup…",
        subtitle =
            when {
                backupAction is BackupAction.Restoring -> "Restoring…"
                errorMessage != null && backupAction !is BackupAction.BackingUp -> errorMessage
                else -> "Replace all data with a backup file you pick"
            },
        // Restore is independent of the picked folder URI — the SAF document picker
        // can read from anywhere — so we don't gate on hasFolder.
        onClick = if (idle) onRestore else null,
        trailing = {
            KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
        },
    )

    if (!hasFolder) {
        Spacer(Modifier.height(12.dp))
        SettingRow(
            icon = KPIconName.Folder,
            title = "About automatic system backup",
            subtitle =
                "Until you pick a folder above, your library is also backed up automatically " +
                    "by Android (if enabled in Settings → System → Backup). Auto Backup is " +
                    "limited to 25 MB and excludes audio downloads.",
        )
    }

    val pending = state.pendingRestoreConfirm
    if (pending != null) {
        AlertDialog(
            onDismissRequest = onCancelRestoreConfirm,
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "This will overwrite your library with the backup from " +
                        "${formatBackupTimestamp(pending.manifest.exportedAtMs)} and the app " +
                        "will close. Open it again from your launcher to finish.",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRestore) {
                    Text("Replace and close")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestoreConfirm) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun lastBackupSubtitle(
    lastBackupAtMs: Long?,
    hasFolder: Boolean,
): String {
    if (lastBackupAtMs == null) return if (hasFolder) "Never" else "Never — no folder picked yet"
    val ageMs = Clock.System.now().toEpochMilliseconds() - lastBackupAtMs
    val minutes = ageMs / 60_000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} hr ago"
        minutes < 60L * 24L * 7L -> "${minutes / (60L * 24L)} day(s) ago"
        else -> formatBackupTimestamp(lastBackupAtMs)
    }
}

private fun formatBackupTimestamp(epochMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val ldt: LocalDateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
    val currentYear = Clock.System.now().toLocalDateTime(tz).year
    val month =
        when (ldt.monthNumber) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mar"
            4 -> "Apr"
            5 -> "May"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Oct"
            11 -> "Nov"
            12 -> "Dec"
            else -> "—"
        }
    // Append the year only when it differs from "now" — avoids "Mar 14, 2026" cluttering
    // the common case while still disambiguating restores from a previous year.
    return if (ldt.year != currentYear) "$month ${ldt.dayOfMonth}, ${ldt.year}" else "$month ${ldt.dayOfMonth}"
}
