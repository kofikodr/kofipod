// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.playlists.DurationRange
import com.kofikodr.kofipod.playlists.PlayState
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.playlists.SmartPlaylistPredicate
import com.kofikodr.kofipod.playlists.SmartPlaylistRepository
import com.kofikodr.kofipod.playlists.SmartPlaylistResolver
import com.kofikodr.kofipod.util.slugifyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Drives the Smart Playlist editor screen.
 *
 * The view-state is derived by combining an in-memory `draft` (name + predicate +
 * isEditMode + isSaving + saveError + availablePodcasts) with the live matched-episode
 * facts streamed from [SmartPlaylistResolver]. Each predicate change (via the various
 * `toggle*` / `set*` methods) flows through `flatMapLatest` so the resolver re-evaluates
 * against the freshest predicate, surfacing both `matchedCount` and the first five
 * episode titles for the preview card.
 *
 * `playlistId == null` is create-mode; non-null is edit-mode (state is pre-filled from
 * [SmartPlaylistRepository.observe] on init). [save] generates a slug-based id in
 * create-mode (collision-safe via [slugifyName]) and reuses the existing id in edit-mode.
 *
 * Network/IO-style side effects (`save`, `delete`, the initial podcasts fetch) hop to
 * `Dispatchers.Default` rather than `Dispatchers.IO`, since the latter is JVM-only and
 * would break iOS compile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistEditorViewModel(
    private val playlists: SmartPlaylistRepository,
    private val resolver: SmartPlaylistResolver,
    private val library: LibraryRepository,
    private val playlistId: String?,
    initialName: String? = null,
    private val clock: Clock = Clock.System,
    // Injectable so tests can route the off-Main `library.podcastsFlow().first()`
    // hop onto the test scheduler — without this it races the test scheduler and
    // editor-VM tests turn flaky when run together.
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val draft =
        MutableStateFlow(
            SmartPlaylistEditorUiState(
                name = initialName.orEmpty(),
                isEditMode = playlistId != null,
            ),
        )

    val state: StateFlow<SmartPlaylistEditorUiState> =
        draft
            .flatMapLatest { d ->
                resolver.observe(d.predicate).map { matched ->
                    d.copy(
                        matchedCount = matched.size,
                        matchedPreview = matched.take(MATCHED_PREVIEW_LIMIT).map { it.episodeTitle },
                    )
                }
            }
            .stateIn(
                // Eager subscription: the editor is a short-lived modal-style screen, the
                // matched-preview must be live the moment the user opens it (no
                // first-frame "0 matches" flicker), and tests can read `state.value`
                // synchronously after `advanceUntilIdle` without first wiring a
                // collector. WhileSubscribed would defer upstream connection until
                // first collector and is the wrong fit here.
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = draft.value,
            )

    init {
        // Load available podcasts (one-shot — refreshes on VM re-init).
        viewModelScope.launch {
            val podcasts =
                withContext(defaultDispatcher) {
                    library.podcastsFlow().first().map { PodcastChoice(it.id, it.title) }
                }
            draft.update { it.copy(availablePodcasts = podcasts) }
        }
        // Pre-fill in edit-mode.
        if (playlistId != null) {
            viewModelScope.launch {
                val existing = playlists.observe(playlistId).first()
                if (existing != null) {
                    draft.update {
                        it.copy(
                            name = existing.name,
                            predicate = existing.predicate,
                            isEditMode = true,
                        )
                    }
                }
            }
        }
    }

    fun setName(name: String) {
        draft.update { it.copy(name = name, saveError = null) }
    }

    fun toggleState(target: PlayState?) {
        draft.update { it.copy(predicate = it.predicate.copy(state = target)) }
    }

    /**
     * Updates the duration range. Pass `null` for either bound to clear that bound; if
     * both end up `null` the range itself is cleared (matches the evaluator's "no filter"
     * semantics — see [DurationRange] in [SmartPlaylistPredicate]).
     */
    fun setDurationRange(
        min: Int?,
        max: Int?,
    ) {
        draft.update {
            val newRange = if (min == null && max == null) null else DurationRange(min, max)
            it.copy(predicate = it.predicate.copy(durationRange = newRange))
        }
    }

    fun togglePodcast(podcastId: String) {
        draft.update {
            val current = it.predicate.podcastIds ?: emptySet()
            val next =
                if (podcastId in current) current - podcastId else current + podcastId
            // Empty set ⇒ store null so the predicate cleanly says "no filter".
            it.copy(predicate = it.predicate.copy(podcastIds = next.ifEmpty { null }))
        }
    }

    fun setMaxAgeDays(days: Int?) {
        draft.update { it.copy(predicate = it.predicate.copy(maxAgeDays = days)) }
    }

    /** Cycles `null → true → false → null`. */
    fun cycleHasTranscript() {
        draft.update {
            val next =
                when (it.predicate.hasTranscript) {
                    null -> true
                    true -> false
                    false -> null
                }
            it.copy(predicate = it.predicate.copy(hasTranscript = next))
        }
    }

    /** Bistable `null → true → null`. */
    fun toggleDownloadedOnly() {
        draft.update {
            val next = if (it.predicate.downloadedOnly == true) null else true
            it.copy(predicate = it.predicate.copy(downloadedOnly = next))
        }
    }

    /** Cycles `null → true → false → null`. */
    fun cycleHasSnippets() {
        draft.update {
            val next =
                when (it.predicate.hasSnippets) {
                    null -> true
                    true -> false
                    false -> null
                }
            it.copy(predicate = it.predicate.copy(hasSnippets = next))
        }
    }

    /**
     * Persists the draft. Returns `true` on success, `false` if validation fails (blank
     * name) or the save raises a non-cancellation exception. Sets `state.saveError` for
     * blank-name; cancellation is re-thrown so structured concurrency stays intact.
     */
    suspend fun save(): Boolean {
        val current = draft.value
        val trimmedName = current.name.trim()
        if (trimmedName.isBlank()) {
            draft.update { it.copy(saveError = "Name is required") }
            return false
        }
        draft.update { it.copy(isSaving = true, saveError = null) }
        val outcome =
            runCatching {
                val id =
                    playlistId ?: run {
                        val existing = playlists.observeAll().first().map { it.id }.toSet()
                        slugifyName(trimmedName, existing)
                    }
                val createdAtMs =
                    if (playlistId != null) {
                        // Preserve the original creation timestamp so list ordering is stable.
                        playlists.observe(playlistId).first()?.createdAtMs
                            ?: clock.now().toEpochMilliseconds()
                    } else {
                        clock.now().toEpochMilliseconds()
                    }
                playlists.save(
                    SmartPlaylist(
                        id = id,
                        name = trimmedName,
                        predicate = current.predicate,
                        createdAtMs = createdAtMs,
                    ),
                )
            }
                .onFailure { if (it is CancellationException) throw it }
        return if (outcome.isSuccess) {
            draft.update { it.copy(isSaving = false, saveError = null) }
            true
        } else {
            draft.update {
                it.copy(
                    isSaving = false,
                    saveError = outcome.exceptionOrNull()?.message ?: "Save failed",
                )
            }
            false
        }
    }

    /** Deletes the playlist. No-op in create-mode. */
    suspend fun delete() {
        val id = playlistId ?: return
        runCatching { playlists.delete(id) }
            .onFailure { if (it is CancellationException) throw it }
    }

    private companion object {
        const val MATCHED_PREVIEW_LIMIT = 5
    }
}
