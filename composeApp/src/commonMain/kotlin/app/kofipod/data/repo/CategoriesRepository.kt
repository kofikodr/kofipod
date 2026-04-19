// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import com.mr3y.podcastindex.model.Category

interface CategoriesSource {
    fun popular(): List<Category>
}

/**
 * The Podcast Index `categories/list` endpoint returns the same fixed taxonomy
 * that the SDK mirrors as `Category` (112 entries). We surface a curated subset
 * as "popular" starting points for search — no network call needed.
 */
class CategoriesRepository : CategoriesSource {
    override fun popular(): List<Category> = POPULAR

    companion object {
        private val POPULAR: List<Category> =
            listOf(
                Category.TECHNOLOGY,
                Category.COMEDY,
                Category.NEWS,
                Category.TRUE_CRIME,
                Category.SCIENCE,
                Category.HISTORY,
                Category.BUSINESS,
                Category.ARTS,
            )
    }
}
