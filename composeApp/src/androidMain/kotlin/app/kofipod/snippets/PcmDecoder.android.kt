// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max

actual class PcmDecoder(private val context: Context) {
    actual suspend fun decodeMono(
        sourceUriOrPath: String,
        startMs: Long,
        endMs: Long,
    ): DecodedPcm =
        withContext(Dispatchers.IO) {
            require(endMs > startMs) {
                "endMs ($endMs) must be greater than startMs ($startMs)"
            }
            val extractor = MediaExtractor()
            try {
                configureSource(extractor, sourceUriOrPath)
                val trackIndex =
                    selectAudioTrack(extractor)
                        ?: throw SnippetPcmDecodeException("No audio track in $sourceUriOrPath")
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime =
                    format.getString(MediaFormat.KEY_MIME)
                        ?: throw SnippetPcmDecodeException("Audio track missing MIME type")
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(sampleRate > 0 && channels > 0) {
                    "Invalid track format: sampleRate=$sampleRate channels=$channels"
                }
                extractor.seekTo(startMs * MICROS_PER_MS, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val pcm =
                        decodeLoop(
                            extractor = extractor,
                            codec = codec,
                            startMs = startMs,
                            endMs = endMs,
                            channels = channels,
                            sampleRate = sampleRate,
                        )
                    DecodedPcm(samples = pcm, sampleRate = sampleRate)
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SnippetPcmDecodeException) {
                throw e
            } catch (e: Throwable) {
                throw SnippetPcmDecodeException("Failed to decode $sourceUriOrPath", e)
            } finally {
                runCatching { extractor.release() }
            }
        }

    private fun configureSource(
        extractor: MediaExtractor,
        sourceUriOrPath: String,
    ) {
        val isRemote = sourceUriOrPath.startsWith("http://") || sourceUriOrPath.startsWith("https://")
        if (isRemote) {
            extractor.setDataSource(sourceUriOrPath, null as Map<String, String>?)
        } else {
            // setDataSource(String) tolerates file:// URIs and bare paths;
            // FileInputStream + FileDescriptor would be more defensive but
            // the upstream caller controls the path so a simple call is fine.
            val path =
                if (sourceUriOrPath.startsWith("file://")) {
                    Uri.parse(sourceUriOrPath).path ?: sourceUriOrPath.removePrefix("file://")
                } else {
                    sourceUriOrPath
                }
            extractor.setDataSource(File(path).absolutePath)
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private suspend fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startMs: Long,
        endMs: Long,
        channels: Int,
        sampleRate: Int,
    ): ShortArray {
        val out = ShortBuffer(initialCapacity = max(sampleRate, MIN_BUFFER_SAMPLES))
        val info = BufferInfo()
        var inputDone = false
        var outputDone = false
        // Seek lands on the previous sync frame, so we keep samples decoded
        // before startMs but drop them from the final array.
        val startUs = startMs * MICROS_PER_MS
        val endUs = endMs * MICROS_PER_MS
        var leadingSamplesToSkip = -1L // -1 = not yet computed (need first output buffer's PTS)

        while (!outputDone) {
            coroutineContext.ensureActive()

            // Feed input buffers until EOS or past endMs.
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val sampleTime = extractor.sampleTime
                    val buffer = codec.getInputBuffer(inputIndex)
                    if (buffer == null || sampleTime < 0L || sampleTime > endUs) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val readBytes = extractor.readSampleData(buffer, 0)
                        if (readBytes < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, readBytes, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            // Drain output buffers.
            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        // PTS of the *first* sample in this buffer (microseconds).
                        // Used to compute leadingSamplesToSkip on the first hit.
                        val bufferPtsUs = info.presentationTimeUs
                        if (leadingSamplesToSkip < 0L) {
                            val skipUs = (startUs - bufferPtsUs).coerceAtLeast(0L)
                            leadingSamplesToSkip = skipUs * sampleRate / MICROS_PER_S
                        }
                        val outputFormat = codec.outputFormat
                        val pcmEncoding =
                            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                        appendDownmixedMono(
                            buffer = buffer,
                            offset = info.offset,
                            size = info.size,
                            channels = channels,
                            pcmEncoding = pcmEncoding,
                            sink = out,
                        )
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // First buffer not yet produced — formats applied to subsequent dequeues.
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Wait for more input to be processed.
                }
            }
        }

        val mono = out.toShortArray()
        val skip =
            leadingSamplesToSkip
                .coerceAtLeast(0L)
                .coerceAtMost(mono.size.toLong())
                .toInt()
        // Trim tail too. The extractor seek lands on the previous sync frame,
        // and the input loop only stops when sampleTime > endUs, so the codec
        // typically decodes one frame's worth of samples (≈23 ms @ 44.1 kHz)
        // past endMs. Without this trim, those late samples would feed the
        // last bars of the envelope, which would visualise audio outside the
        // requested clip range.
        val expectedSamples = (endMs - startMs) * sampleRate / MS_PER_SECOND
        val end =
            (skip.toLong() + expectedSamples)
                .coerceAtMost(mono.size.toLong())
                .toInt()
        if (skip == 0 && end == mono.size) return mono
        return mono.copyOfRange(skip, end)
    }

    private fun appendDownmixedMono(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        pcmEncoding: Int,
        sink: ShortBuffer,
    ) {
        // Slice + LE order — codec output buffers are little-endian PCM.
        val slice =
            buffer.duplicate().apply {
                position(offset)
                limit(offset + size)
                order(ByteOrder.LITTLE_ENDIAN)
            }
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shortBuf = slice.asShortBuffer()
                val totalShorts = shortBuf.remaining()
                val frames = totalShorts / channels
                if (channels == 1) {
                    sink.ensureCapacity(sink.size + frames)
                    for (i in 0 until frames) sink.append(shortBuf.get())
                } else {
                    sink.ensureCapacity(sink.size + frames)
                    for (i in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += shortBuf.get().toInt()
                        sink.append((sum / channels).toShort())
                    }
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuf = slice.asFloatBuffer()
                val totalFloats = floatBuf.remaining()
                val frames = totalFloats / channels
                sink.ensureCapacity(sink.size + frames)
                if (channels == 1) {
                    for (i in 0 until frames) sink.append(floatToShort(floatBuf.get()))
                } else {
                    for (i in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) sum += floatBuf.get()
                        sink.append(floatToShort(sum / channels))
                    }
                }
            }
            else -> {
                throw SnippetPcmDecodeException(
                    "Unsupported PCM encoding: $pcmEncoding (expected 16-bit or float)",
                )
            }
        }
    }

    private fun floatToShort(f: Float): Short {
        val clamped = f.coerceIn(-1f, 1f)
        return (clamped * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Growable Short sink. Avoids `ArrayList<Short>` boxing overhead which
     * would matter for ~30 seconds × 44.1 kHz = 1.3M samples per snippet.
     */
    private class ShortBuffer(initialCapacity: Int) {
        private var array: ShortArray = ShortArray(initialCapacity.coerceAtLeast(MIN_BUFFER_SAMPLES))
        var size: Int = 0
            private set

        fun append(value: Short) {
            ensureCapacity(size + 1)
            array[size++] = value
        }

        fun ensureCapacity(required: Int) {
            if (required <= array.size) return
            var newCap = array.size * 2
            while (newCap < required) newCap *= 2
            array = array.copyOf(newCap)
        }

        fun toShortArray(): ShortArray = array.copyOf(size)
    }

    private companion object {
        const val MICROS_PER_MS = 1_000L
        const val MICROS_PER_S = 1_000_000L
        const val MS_PER_SECOND = 1_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MIN_BUFFER_SAMPLES = 8_192
    }
}
