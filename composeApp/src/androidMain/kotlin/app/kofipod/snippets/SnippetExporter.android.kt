// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
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

    private fun toUri(sourceUriOrPath: String): Uri =
        if (sourceUriOrPath.startsWith("http://") || sourceUriOrPath.startsWith("https://")) {
            Uri.parse(sourceUriOrPath)
        } else {
            Uri.fromFile(File(sourceUriOrPath))
        }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
