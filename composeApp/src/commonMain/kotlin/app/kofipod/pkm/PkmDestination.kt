// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.pkm.connections.ConnectionKind

/**
 * The user-facing pickable dimension on the Export action sheet. Maps to
 * a [ConnectionKind] for sinks that need a connection row; Clipboard and
 * ShareFile are zero-auth so they never appear in PkmConnection.
 */
enum class PkmDestination(val connectionKind: ConnectionKind?) {
    Clipboard(null),
    ShareFile(null),
    Obsidian(ConnectionKind.Obsidian),
    Readwise(ConnectionKind.Readwise),
}
