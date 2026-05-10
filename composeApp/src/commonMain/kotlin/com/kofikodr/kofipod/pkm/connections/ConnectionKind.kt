// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.connections

enum class ConnectionKind(val wire: String) {
    Markdown("markdown"),
    Obsidian("obsidian"),
    Readwise("readwise"),
    Notion("notion"),
    ;

    companion object {
        fun fromWire(value: String): ConnectionKind? = entries.firstOrNull { it.wire == value }
    }
}
