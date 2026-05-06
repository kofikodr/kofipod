// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.SpannableString
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual class SnippetExporter(private val context: Context) {
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
     *  - Image sequence: cover-card Bitmap (waveform bars overlaid in lower
     *    third) exported via [WaveformBitmapRenderer], written to a temp PNG
     *    and fed as an image-source MediaItem. Transformer loops the static
     *    frame to match the clip duration.
     *  - Audio sequence: audio source clipped to [snippet.startMs, endMs] with
     *    the video track removed.
     *  - Caption: when [captionText] is non-blank, an [OverlayEffect] carrying a
     *    [TextOverlay] burns the caption string into every frame.
     *
     * The temp PNG frame file is deleted in the finally block regardless of
     * export success or failure.
     *
     * @param coverArtUriOrPath Local file path to cover art, or null. Remote
     *   URLs are not passed through to the bitmap renderer (caller should
     *   supply a locally-cached path or null).
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
    ): Result<String> =
        withContext(Dispatchers.Main) {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            // 1. Pre-render the cover-card Bitmap to a temp PNG so Transformer can
            //    consume it as an image MediaItem (looped across the clip duration).
            //    Remote URLs are deliberately excluded — only pass local paths.
            val localCoverPath =
                coverArtUriOrPath?.takeIf { !it.startsWith("http://") && !it.startsWith("https://") }
            val coverFrame =
                WaveformBitmapRenderer.renderWaveformCard(
                    samples = waveformSamples,
                    coverArtPath = localCoverPath,
                )
            // Use a timestamp suffix to avoid collisions if the same snippet is
            // exported concurrently (e.g. double-tap). parentFile fallback to
            // cacheDir guards against a bare-filename outputPath.
            val frameDir = outputFile.absoluteFile.parentFile ?: context.cacheDir
            val frameFile = File(frameDir, "${snippet.id}-frame-${System.currentTimeMillis()}.png")
            frameFile.outputStream().use { coverFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            coverFrame.recycle()

            val durationUs = (snippet.endMs - snippet.startMs) * 1_000L

            // 2. Image MediaItem — the temp PNG as the video track. Transformer loops
            //    the static frame across the full clip duration.
            val videoEffects = buildVideoEffects(captionText)
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
            //    Use Builder to avoid deprecated constructors in Media3 1.5.1.
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

            // Progress polling — same pattern as exportMp3.
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
                // Clean up the temp frame PNG regardless of export outcome.
                frameFile.delete()
            }
        }

    /**
     * Builds the list of video [androidx.media3.common.Effect]s to attach to
     * the image MediaItem. Returns a singleton list with an [OverlayEffect]
     * carrying a [TextOverlay] when [captionText] is non-blank; empty list
     * otherwise (no GPU effect pipeline needed for a static image without text).
     */
    private fun buildVideoEffects(captionText: String?): List<Effect> =
        if (!captionText.isNullOrBlank()) {
            listOf(
                OverlayEffect(
                    listOf(
                        TextOverlay.createStaticTextOverlay(SpannableString(captionText)),
                    ),
                ),
            )
        } else {
            emptyList()
        }

    private fun toUri(sourceUriOrPath: String): Uri =
        if (sourceUriOrPath.startsWith("http://") || sourceUriOrPath.startsWith("https://")) {
            Uri.parse(sourceUriOrPath)
        } else {
            Uri.fromFile(File(sourceUriOrPath))
        }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val VIDEO_FRAME_RATE = 30
    }
}
