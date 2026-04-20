// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Tee processor that forwards PCM downstream unchanged while feeding samples into
 * [KofipodAudioAnalyzer]. Only 16-bit PCM is analyzed; other encodings still pass through,
 * they simply produce no visualizer data.
 */
class KofipodAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // We don't transform audio — accept any format, output matches input.
        KofipodAudioAnalyzer.configure(
            sampleRate = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
            pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT,
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        KofipodAudioAnalyzer.submit16Bit(inputBuffer)
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onReset() {
        KofipodAudioAnalyzer.reset()
    }
}
