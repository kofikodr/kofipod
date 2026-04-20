// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class KofipodPlayer {
    private val _state = MutableStateFlow(PlayerState())
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()

    // TODO: tap AVAudioEngine / MTAudioProcessingTap to produce real levels on iOS.
    private val _audioLevels = MutableStateFlow(FloatArray(AUDIO_LEVEL_BAR_COUNT))
    actual val audioLevels: StateFlow<FloatArray> = _audioLevels.asStateFlow()

    actual fun play(episode: PlayableEpisode) { /* TODO AVPlayer */ }

    actual fun pause() {}

    actual fun resume() {}

    actual fun seekTo(ms: Long) {}

    actual fun setSpeed(speed: Float) {}

    actual fun skipForward() {}

    actual fun skipBack() {}

    actual fun setSleepTimer(ms: Long?) {}

    actual fun stop() {}

    actual fun release() {}
}
