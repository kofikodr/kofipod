// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReadwiseCreateRequest(
    val highlights: List<ReadwiseHighlightCreate>,
)

@Serializable
data class ReadwiseHighlightCreate(
    val text: String,
    val title: String,
    val author: String? = null,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_type") val sourceType: String = "podcast",
    val note: String? = null,
    @SerialName("highlighted_at") val highlightedAt: String? = null,
)

@Serializable
data class ReadwiseCreateResponseItem(val id: Long)

@Serializable
data class ReadwiseUpdateRequest(
    val text: String? = null,
    val note: String? = null,
)
