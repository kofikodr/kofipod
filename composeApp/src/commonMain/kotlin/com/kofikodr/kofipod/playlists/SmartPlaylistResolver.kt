// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * Composition seam over [EpisodeFactsRepository] + [PredicateEvaluator].
 *
 * Consumers (the editor's live count, the detail screen's matched list) observe
 * a single [SmartPlaylistPredicate] and receive a fresh, evaluator-filtered
 * snapshot of [EpisodeFacts] every time the underlying facts flow re-emits.
 *
 * [clock] is injected so tests (and any future "as-of" preview UI) can pin the
 * `nowMs` used for relative-age filters; production wires [Clock.System].
 */
class SmartPlaylistResolver(
    private val facts: EpisodeFactsRepository,
    private val evaluator: PredicateEvaluator,
    private val clock: Clock = Clock.System,
) {
    fun observe(predicate: SmartPlaylistPredicate): Flow<List<EpisodeFacts>> =
        facts.observeAll().map { all ->
            evaluator.evaluate(predicate, all, clock.now().toEpochMilliseconds())
        }
}
