// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

sealed class SnippetSource {
    data class Local(val path: String) : SnippetSource()

    data class Remote(val url: String) : SnippetSource()

    data object None : SnippetSource()
}

class SnippetSourceResolver(private val fileChecker: FileCheckerApi) {
    fun resolve(
        localPath: String?,
        enclosureUrl: String,
    ): SnippetSource {
        if (!localPath.isNullOrBlank() && fileChecker.exists(localPath)) {
            return SnippetSource.Local(localPath)
        }
        if (enclosureUrl.isNotBlank()) {
            return SnippetSource.Remote(enclosureUrl)
        }
        return SnippetSource.None
    }
}
