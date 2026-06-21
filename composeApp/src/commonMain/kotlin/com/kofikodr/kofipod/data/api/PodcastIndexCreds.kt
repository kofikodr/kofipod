// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/** A Podcast Index API key + secret pair. Both must be non-blank to be usable. */
data class PodcastIndexCreds(
    val key: String,
    val secret: String,
) {
    val isUsable: Boolean get() = key.isNotBlank() && secret.isNotBlank()
}
