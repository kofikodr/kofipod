// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-audio visualizer backend.
 *
 * Singleton so the playback service (writer) and UI process (reader) share state — they
 * run in the same OS process because [KofipodPlaybackService] has no `android:process=`
 * attribute, so a plain object works as a bus.
 *
 * Only 16-bit PCM input is analyzed. Other encodings pass through untouched and the
 * visualizer stays at zero. Analysis runs at ~20 Hz (throttled by wallclock) so even an
 * in-place FFT on every audio buffer doesn't show up in profiling.
 */
object KofipodAudioAnalyzer {
    private const val FFT_SIZE = 256
    private const val HALF = FFT_SIZE / 2
    private const val EMIT_INTERVAL_NANOS = 40_000_000L
    private const val DECAY = 0.78f
    private const val DB_FLOOR = -60f

    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var ringFilled = 0

    private var channels = 2

    private val window =
        FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }

    private val smoothed = FloatArray(AUDIO_LEVEL_BAR_COUNT)

    private val binRanges: Array<IntRange> =
        Array(AUDIO_LEVEL_BAR_COUNT) { b ->
            val t0 = b.toDouble() / AUDIO_LEVEL_BAR_COUNT
            val t1 = (b + 1).toDouble() / AUDIO_LEVEL_BAR_COUNT
            val minBin = 1
            val maxBin = HALF - 1
            val span = maxBin.toDouble() / minBin
            val lo = (minBin * span.pow(t0)).toInt().coerceAtLeast(minBin)
            val hi = (minBin * span.pow(t1)).toInt().coerceIn(lo, maxBin)
            lo..hi
        }

    private var lastEmitNanos = 0L
    private var analysisActive = false

    private val _levels = MutableStateFlow(FloatArray(AUDIO_LEVEL_BAR_COUNT))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    @Synchronized
    fun configure(
        sampleRate: Int,
        channelCount: Int,
        pcm16: Boolean,
    ) {
        this.channels = channelCount.coerceAtLeast(1)
        this.analysisActive = pcm16
        ringPos = 0
        ringFilled = 0
        smoothed.fill(0f)
        _levels.value = FloatArray(AUDIO_LEVEL_BAR_COUNT)
    }

    @Synchronized
    fun reset() {
        analysisActive = false
        ringPos = 0
        ringFilled = 0
        smoothed.fill(0f)
        _levels.value = FloatArray(AUDIO_LEVEL_BAR_COUNT)
    }

    /** Reads PCM 16-bit samples from [buffer] non-destructively. Does not move its position. */
    @Synchronized
    fun submit16Bit(buffer: ByteBuffer) {
        if (!analysisActive) return
        val dup = buffer.duplicate().order(buffer.order() ?: ByteOrder.nativeOrder())
        val shorts = dup.asShortBuffer()
        val ch = channels
        while (shorts.remaining() >= ch) {
            var sum = 0
            for (c in 0 until ch) sum += shorts.get().toInt()
            ring[ringPos] = (sum.toFloat() / ch) / SHORT_SCALE
            ringPos = (ringPos + 1) % FFT_SIZE
            if (ringFilled < FFT_SIZE) ringFilled++
        }
        val now = System.nanoTime()
        if (ringFilled >= FFT_SIZE && now - lastEmitNanos >= EMIT_INTERVAL_NANOS) {
            lastEmitNanos = now
            emit()
        }
    }

    private fun emit() {
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]
        }
        fftInPlace(re, im)

        val result = FloatArray(AUDIO_LEVEL_BAR_COUNT)
        for (b in 0 until AUDIO_LEVEL_BAR_COUNT) {
            val range = binRanges[b]
            var peak = 0f
            for (k in range) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            val normalized = peak / (FFT_SIZE / 2f)
            val db =
                if (normalized > 0f) {
                    20f * log10(normalized.coerceAtLeast(MIN_MAGNITUDE))
                } else {
                    DB_FLOOR
                }
            val v = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
            smoothed[b] = max(smoothed[b] * DECAY, v)
            result[b] = smoothed[b]
        }
        _levels.value = result
    }

    private fun fftInPlace(
        real: FloatArray,
        imag: FloatArray,
    ) {
        val n = real.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = real[i]
                real[i] = real[j]
                real[j] = t
                t = imag[i]
                imag[i] = imag[j]
                imag[j] = t
            }
        }
        // Cooley-Tukey butterflies.
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * PI / len
            val wStepReal = cos(angle).toFloat()
            val wStepImag = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until half) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + half] * wReal - imag[i + k + half] * wImag
                    val vImag = real[i + k + half] * wImag + imag[i + k + half] * wReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + half] = uReal - vReal
                    imag[i + k + half] = uImag - vImag
                    val nextWReal = wReal * wStepReal - wImag * wStepImag
                    wImag = wReal * wStepImag + wImag * wStepReal
                    wReal = nextWReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    private const val SHORT_SCALE = 32768f
    private const val MIN_MAGNITUDE = 1e-6f
}
