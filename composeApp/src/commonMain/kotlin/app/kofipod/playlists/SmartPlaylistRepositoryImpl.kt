// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.kofipod.db.SmartPlaylist as DbSmartPlaylist

/**
 * SQLDelight-backed [SmartPlaylistRepository].
 *
 * The [SmartPlaylistPredicate] is encoded as JSON into the `predicateJson` column with
 * `encodeDefaults = false` + `explicitNulls = false` so optional / null fields don't bloat
 * the row. Decode failures (corrupt or future-schema rows) fall back to
 * [SmartPlaylistPredicate.EMPTY] rather than throwing — keeps the observe stream alive so
 * the UI can still surface the row name and let the user delete it.
 */
class SmartPlaylistRepositoryImpl(
    private val db: KofipodDatabase,
) : SmartPlaylistRepository {
    private val q = db.smartPlaylistQueries
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    override fun observeAll(): Flow<List<SmartPlaylist>> =
        q.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<SmartPlaylist?> =
        q.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toDomain() }

    override suspend fun save(playlist: SmartPlaylist) =
        withContext(Dispatchers.Default) {
            q.upsert(
                id = playlist.id,
                name = playlist.name,
                predicateJson = json.encodeToString(SmartPlaylistPredicate.serializer(), playlist.predicate),
                createdAt = playlist.createdAtMs,
            )
        }

    override suspend fun delete(id: String) =
        withContext(Dispatchers.Default) {
            q.delete(id)
        }

    private fun DbSmartPlaylist.toDomain(): SmartPlaylist {
        val predicate =
            runCatching {
                json.decodeFromString(SmartPlaylistPredicate.serializer(), predicateJson)
            }.getOrElse { SmartPlaylistPredicate.EMPTY }
        return SmartPlaylist(
            id = id,
            name = name,
            predicate = predicate,
            createdAtMs = createdAt,
        )
    }
}
