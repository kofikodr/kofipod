// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.SpannableString
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual class SnippetExporter(
    private val context: Context,
    private val pcmDecoder: PcmDecoder,
) {
    @OptIn(DelicateCoroutinesApi::class)
    actual suspend fun exportMp3(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            // Transformer is built on the main thread (Android requirement).
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val mediaItem =
                MediaItem.Builder()
                    .setUri(toUri(sourceUriOrPath))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(snippet.startMs)
                            .setEndPositionMs(snippet.endMs)
                            .build(),
                    )
                    .build()

            val edited =
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true) // audio-only
                    .build()

            val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()

            val deferred = CompletableDeferred<Result<String>>()

            val transformer =
                Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC) // Transformer's MP3 encoder is the muxer's job
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                c: Composition,
                                exportResult: ExportResult,
                            ) {
                                deferred.complete(Result.success(outputFile.absolutePath))
                            }

                            override fun onError(
                                c: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                deferred.complete(Result.failure(exportException))
                            }
                        },
                    )
                    .build()

            // Progress polling — Transformer doesn't push progress; we poll via getProgress.
            // We don't want to block the calling coroutine on progress, so we just attach a
            // simple poller that runs while the deferred is pending.
            val pollerJob =
                GlobalScope.launch(Dispatchers.Main) {
                    val holder = ProgressHolder()
                    while (!deferred.isCompleted) {
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }

            try {
                transformer.start(composition, outputPath)
                val result = deferred.await()
                pollerJob.cancel()
                result
            } catch (t: Throwable) {
                pollerJob.cancel()
                try {
                    transformer.cancel()
                } catch (_: Throwable) {
                    // Best-effort cancel; the original failure is what we surface.
                }
                Result.failure(t)
            }
        }

    /**
     * Exports a snippet as an MP4 using a Media3 Transformer Composition graph:
     *  - Image sequence: cover-only background bitmap (cover art on dark bg, no bars)
     *    written once to a temp PNG and looped by Transformer for the clip duration.
     *  - Bars overlay: a [BitmapOverlay] subclass returns a fresh bars-only ARGB
     *    bitmap on every `getBitmap(presentationTimeUs)` call. Per-frame bar values
     *    come from a precomputed [AmplitudeEnvelope] derived from the source audio,
     *    so the bars dance with the audio like a music-video visualiser.
     *  - Audio sequence: audio source clipped to [snippet.startMs, endMs] with the
     *    video track removed.
     *  - Caption: when [captionText] is non-blank, a [TextOverlay] burns the
     *    caption string into every frame on top of the bars.
     *
     * Cover-art handling:
     *  - Local file paths are passed straight to [WaveformBitmapRenderer.renderCoverBackground].
     *  - HTTP(S) URLs are downloaded via Coil into a temp PNG before render. Without
     *    this step the renderer silently drops remote URLs and the cover region is
     *    blank.
     *
     * Audio precompute: runs on Dispatchers.IO before Transformer.start. Decode
     * failure is a hard fail (user-locked decision: no fallback to synthetic bars)
     * — exportMp4 returns Result.failure and no Transformer ever starts. The temp
     * frame PNG (and any temp cover PNG) are deleted in the finally block regardless
     * of export success or failure.
     */
    @OptIn(DelicateCoroutinesApi::class)
    actual suspend fun exportMp4(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        coverArtUriOrPath: String?,
        captionText: String?,
        waveformSamples: WaveformSamples,
        onProgress: (Float) -> Unit,
    ): Result<String> {
        // Resolve the cover into a local file path BEFORE switching to the main
        // dispatcher — Coil execute() suspends and we don't want to block the UI thread.
        val (localCoverPath, coverTempFile) = resolveCoverArtPath(coverArtUriOrPath)
        try {
            val envelope =
                try {
                    precomputeEnvelope(sourceUriOrPath, snippet, waveformSamples.bars.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Hard fail per user-locked decision — no fallback to synthetic
                    // wiggle. Surface the decode error so the user knows the snippet
                    // did not render.
                    return Result.failure(e)
                }
            return doExportMp4(
                snippet = snippet,
                sourceUriOrPath = sourceUriOrPath,
                outputPath = outputPath,
                localCoverPath = localCoverPath,
                captionText = captionText,
                envelope = envelope,
                onProgress = onProgress,
            )
        } finally {
            // Outer cleanup — survives cancellation between resolveCoverArtPath
            // and the doExportMp4 main-thread block.
            coverTempFile?.delete()
        }
    }

    /**
     * Decode the clipped audio range, then build a per-frame, per-bar amplitude
     * envelope. The envelope drives [AnimatedBarsOverlay]'s per-frame bitmap so
     * the bars track the source audio amplitude.
     *
     * Frame count rounds up so a clip whose duration falls between two video
     * frames still gets a non-zero envelope. The minimum of 1 frame protects
     * against edge cases where duration < 33ms (one frame at 30 fps).
     */
    private suspend fun precomputeEnvelope(
        sourceUriOrPath: String,
        snippet: Snippet,
        barCount: Int,
    ): AmplitudeEnvelope {
        val durationMs = snippet.endMs - snippet.startMs
        // Ceiling division: any clip whose duration isn't a multiple of
        // (1000/fps) ms otherwise produces an envelope one frame short of the
        // encoder's frame count. The overlay's coerceIn would then freeze the
        // last encoder frame on bars from ~33 ms earlier.
        val frameCount =
            ((durationMs * VIDEO_FRAME_RATE + MS_PER_SECOND - 1) / MS_PER_SECOND)
                .toInt()
                .coerceAtLeast(1)
        val pcm = pcmDecoder.decodeMono(sourceUriOrPath, snippet.startMs, snippet.endMs)
        return AmplitudeEnvelopeBuilder.build(
            pcm = pcm.samples,
            sampleRate = pcm.sampleRate,
            frameCount = frameCount,
            barCount = barCount,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun doExportMp4(
        snippet: Snippet,
        sourceUriOrPath: String,
        outputPath: String,
        localCoverPath: String?,
        captionText: String?,
        envelope: AmplitudeEnvelope,
        onProgress: (Float) -> Unit,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            // 1. Pre-render the cover-only background (no bars) to a temp PNG so
            //    Transformer can consume it as a looping image MediaItem. The bars
            //    are added back in via a per-frame BitmapOverlay below.
            val frameDir = outputFile.absoluteFile.parentFile ?: context.cacheDir
            // Random UUID suffix avoids collisions when two renders fire in the same
            // millisecond (System.currentTimeMillis is not collision-resistant under
            // fast back-to-back enqueues).
            val frameFile = File(frameDir, "${snippet.id}-frame-${java.util.UUID.randomUUID()}.png")
            withContext(Dispatchers.IO) {
                val coverFrame =
                    WaveformBitmapRenderer.renderCoverBackground(
                        coverArtPath = localCoverPath,
                    )
                try {
                    frameFile.outputStream().use {
                        coverFrame.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    coverFrame.recycle()
                }
            }

            val durationUs = (snippet.endMs - snippet.startMs) * 1_000L

            // 2. Image MediaItem — temp PNG as the static video track.
            val videoEffects = buildVideoEffects(envelope, captionText)
            val imageItem =
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(frameFile)))
                    .setDurationUs(durationUs)
                    .setFrameRate(VIDEO_FRAME_RATE)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

            // 3. Audio MediaItem — clipped to [startMs, endMs], video track stripped.
            val audioItem =
                EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(toUri(sourceUriOrPath))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(snippet.startMs)
                                .setEndPositionMs(snippet.endMs)
                                .build(),
                        )
                        .build(),
                ).setRemoveVideo(true).build()

            // 4. Composition: image sequence (video track) + audio sequence.
            val composition =
                Composition.Builder(
                    EditedMediaItemSequence.Builder(imageItem).build(),
                    EditedMediaItemSequence.Builder(audioItem).build(),
                ).build()

            val deferred = CompletableDeferred<Result<String>>()

            val transformer =
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                c: Composition,
                                exportResult: ExportResult,
                            ) {
                                deferred.complete(Result.success(outputFile.absolutePath))
                            }

                            override fun onError(
                                c: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                deferred.complete(Result.failure(exportException))
                            }
                        },
                    )
                    .build()

            val pollerJob =
                GlobalScope.launch(Dispatchers.Main) {
                    val holder = ProgressHolder()
                    while (!deferred.isCompleted) {
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }

            try {
                transformer.start(composition, outputPath)
                val result = deferred.await()
                pollerJob.cancel()
                result
            } catch (t: Throwable) {
                pollerJob.cancel()
                try {
                    transformer.cancel()
                } catch (_: Throwable) {
                    // Best-effort cancel; the original failure is what we surface.
                }
                Result.failure(t)
            } finally {
                frameFile.delete()
            }
        }

    /**
     * If [coverArtUriOrPath] is null or already a local path, return it (and a
     * null temp file). If it's an HTTP(S) URL, download via Coil and write the
     * decoded bitmap to a temp PNG; return that path plus the [File] handle so
     * the caller can clean it up.
     *
     * Failures (network, decode, write) fall back to a null cover path — the
     * render still succeeds, just without album art, rather than aborting.
     */
    private suspend fun resolveCoverArtPath(coverArtUriOrPath: String?): Pair<String?, File?> {
        if (coverArtUriOrPath.isNullOrBlank()) return null to null
        val isRemote = coverArtUriOrPath.startsWith("http://") || coverArtUriOrPath.startsWith("https://")
        if (!isRemote) return coverArtUriOrPath to null
        val bitmap = fetchCoverBitmap(coverArtUriOrPath) ?: return null to null
        return withContext(Dispatchers.IO) {
            val coverDir = File(context.cacheDir, "snippets").apply { mkdirs() }
            val tempFile = File(coverDir, "cover-${java.util.UUID.randomUUID()}.png")
            try {
                tempFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tempFile.absolutePath to tempFile
            } catch (e: Throwable) {
                // Honor coroutine cancellation — swallowing it would let a cancelled
                // export proceed into the Transformer.start path.
                if (e is CancellationException) {
                    tempFile.delete()
                    throw e
                }
                tempFile.delete()
                null to null
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Coil-backed bitmap fetch for a remote URL. Mirrors the
     * [app.kofipod.background.Notifier.fetchBitmap] pattern: hardware bitmaps
     * are disabled (we draw via Canvas, which doesn't accept Config.HARDWARE)
     * and the size is capped at the cover-card target so we don't decode 3000×3000
     * podcast art at full resolution into the render service's heap.
     */
    private suspend fun fetchCoverBitmap(url: String): Bitmap? {
        val outcome =
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .size(Size(COVER_TARGET_PX, COVER_TARGET_PX))
                        .allowHardware(false)
                        .build()
                val result = loader.execute(request)
                if (result !is SuccessResult) return@runCatching null
                (result.image as? BitmapImage)?.bitmap
            }
        // Honor coroutine cancellation — without re-throwing, a cancelled export
        // would silently downgrade to the no-cover path and continue.
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return outcome.getOrNull()
    }

    /**
     * Builds the video-track [Effect]s applied to the static cover image:
     *  - Always: a [BitmapOverlay] re-rendered per frame to animate the bars.
     *  - When caption is non-blank: a [TextOverlay] burned on top.
     */
    private fun buildVideoEffects(
        envelope: AmplitudeEnvelope,
        captionText: String?,
    ): List<Effect> {
        val overlays = mutableListOf<TextureOverlay>()
        overlays.add(AnimatedBarsOverlay(envelope = envelope))
        if (!captionText.isNullOrBlank()) {
            overlays.add(TextOverlay.createStaticTextOverlay(SpannableString(captionText)))
        }
        return listOf(OverlayEffect(overlays))
    }

    private fun toUri(sourceUriOrPath: String): Uri =
        if (sourceUriOrPath.startsWith("http://") || sourceUriOrPath.startsWith("https://")) {
            Uri.parse(sourceUriOrPath)
        } else {
            Uri.fromFile(File(sourceUriOrPath))
        }

    /**
     * [BitmapOverlay] that returns a fresh bars-only bitmap on each frame, with
     * per-bar values pulled from a precomputed [AmplitudeEnvelope] keyed off the
     * source-audio amplitude. Allocating a new ARGB_8888 bitmap per call is
     * intentional — Media3's overlay GPU upload path keys on the [Bitmap]
     * reference, so reusing one would cause it to skip the upload and the bars
     * would freeze.
     *
     * Index clamp: Media3 may invoke [getBitmap] for timestamps slightly past
     * the clip's last frame (encoder padding); the envelope's own bounds clamp
     * absorbs that without throwing.
     */
    private class AnimatedBarsOverlay(
        private val envelope: AmplitudeEnvelope,
    ) : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val frameIdx =
                ((presentationTimeUs * VIDEO_FRAME_RATE) / MICROS_PER_SECOND)
                    .toInt()
                    .coerceIn(0, envelope.frameCount - 1)
            val bars = envelope.barsAt(frameIdx)
            return WaveformBitmapRenderer.renderWaveformBarsOverlay(WaveformSamples(bars))
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val VIDEO_FRAME_RATE = 30
        const val MICROS_PER_SECOND = 1_000_000L
        const val MS_PER_SECOND = 1_000L
        const val COVER_TARGET_PX = 1080
    }
}
