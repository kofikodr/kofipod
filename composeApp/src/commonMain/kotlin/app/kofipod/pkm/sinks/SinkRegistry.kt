// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import app.kofipod.pkm.connections.ConnectionKind

/**
 * Registry mapping each [ConnectionKind] to its corresponding [ExportSink].
 * Zero-auth sinks (Clipboard, ShareFile) are NOT in the registry — they are
 * passed directly to [app.kofipod.pkm.PkmExportCoordinator] as named parameters
 * so routing via registry vs. direct field is explicit at the call site.
 *
 * Immutable by design: all bindings are established at DI startup and never
 * mutated at runtime. Adding a new sink requires only a new map entry in
 * [app.kofipod.di.CommonModule].
 */
class SinkRegistry(private val sinks: Map<ConnectionKind, ExportSink>) {
    fun forKind(kind: ConnectionKind): ExportSink? = sinks[kind]
}
