// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import com.kofikodr.kofipod.pkm.connections.ConnectionKind

/**
 * Registry mapping each [ConnectionKind] to its corresponding [ExportSink].
 * Zero-auth sinks (Clipboard, ShareFile) are NOT in the registry — they are
 * passed directly to [com.kofikodr.kofipod.pkm.PkmExportCoordinator] as named parameters
 * so routing via registry vs. direct field is explicit at the call site.
 *
 * Immutable by design: all bindings are established at DI startup and never
 * mutated at runtime. Adding a new sink requires only a new map entry in
 * [com.kofikodr.kofipod.di.CommonModule].
 */
class SinkRegistry(private val sinks: Map<ConnectionKind, ExportSink>) {
    fun forKind(kind: ConnectionKind): ExportSink? = sinks[kind]
}
