// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** iOS doesn't sideload — no path is ever stored. */
class IosLocalApkPathStore : LocalApkPathStore {
    override fun pathNow(): String? = null

    override fun setPath(path: String?) { /* no-op */ }

    override fun pathFlow(): Flow<String?> = flowOf(null)
}
