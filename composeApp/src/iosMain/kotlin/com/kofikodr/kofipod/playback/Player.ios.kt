// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class KofipodPlayer : Player {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    // TODO: tap AVAudioEngine / MTAudioProcessingTap to produce real levels on iOS.
    private val _audioLevels = MutableStateFlow(FloatArray(AUDIO_LEVEL_BAR_COUNT))
    override val audioLevels: StateFlow<FloatArray> = _audioLevels.asStateFlow()

    override fun play(episode: PlayableEpisode) { /* TODO AVPlayer */ }

    override fun pause() {}

    override fun resume() {}

    override fun seekTo(ms: Long) {}

    override fun setSpeed(speed: Float) {}

    override fun skipForward() {}

    override fun skipBack() {}

    override fun setSleepTimer(ms: Long?) {}

    override fun stop() {}

    override fun release() {}
}
