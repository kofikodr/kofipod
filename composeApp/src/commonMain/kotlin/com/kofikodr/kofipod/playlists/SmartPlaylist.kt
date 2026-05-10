// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

data class SmartPlaylist(
    val id: String,
    val name: String,
    val predicate: SmartPlaylistPredicate,
    val createdAtMs: Long,
)
