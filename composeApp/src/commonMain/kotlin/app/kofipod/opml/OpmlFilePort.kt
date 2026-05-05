// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

/**
 * Surfaces the platform file-picker surface to common code without pulling Android types
 * into commonMain. Mirrors the [app.kofipod.ui.screens.settings.UpdateActionPort] shape.
 *
 * Android binds this to the flow-driven [app.kofipod.opml.AndroidOpmlFilePort], which is
 * paired with a Compose host (`OpmlPickerHost`) hoisted in [app.kofipod.ui.shell.AppShell]
 * so the SAF launchers stay rooted regardless of which screen triggered the request.
 *
 * iOS binds a no-op so Koin's graph stays consistent across targets.
 */
interface OpmlFilePort {
    /** `null` = user cancelled. Throws if the picker can't open or the read fails. */
    suspend fun pickImport(): ByteArray?

    /** Returns `true` on save, `false` on cancel. Throws on write failure. */
    suspend fun saveExport(
        suggestedFilename: String,
        content: String,
    ): Boolean
}
