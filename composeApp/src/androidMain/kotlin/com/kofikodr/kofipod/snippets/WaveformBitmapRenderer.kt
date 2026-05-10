// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import java.io.File

/**
 * Renders the cover-card frame used as the MP4's video track.
 *
 * Two production paths:
 *  1. [renderCoverBackground] — dark bg + cover art square. Static across the
 *     entire clip; written to a single temp PNG and consumed by Transformer as
 *     the looping image source.
 *  2. [renderWaveformBarsOverlay] — transparent bitmap with only the waveform
 *     bars in their lower-third lane. Re-rendered per video frame (driven by
 *     `presentationTimeUs`) and returned from a `BitmapOverlay` subclass so the
 *     bars animate VU-meter style in the final MP4.
 *
 * The legacy single-pass [renderWaveformCard] is preserved for the editor preview
 * — it composites both layers into one bitmap.
 *
 * Android-only because [Bitmap] and [Canvas] are Android platform types.
 */
internal object WaveformBitmapRenderer {
    /**
     * Static background frame: dark canvas + cover art square. No bars.
     *
     * @param coverArtPath  Absolute local file path to the episode artwork, or
     *                      null if unavailable. Remote URLs are not supported —
     *                      callers must download to a local path first.
     * @param widthPx       Output bitmap width in pixels.
     * @param heightPx      Output bitmap height in pixels.
     */
    fun renderCoverBackground(
        coverArtPath: String?,
        widthPx: Int = DEFAULT_WIDTH_PX,
        heightPx: Int = DEFAULT_HEIGHT_PX,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor(DARK_BG_HEX))
        drawCoverArt(canvas, coverArtPath, widthPx, heightPx)
        return bmp
    }

    /**
     * Per-frame transparent overlay containing only the waveform bars in their
     * lower-third lane. Caller passes the per-frame bars from
     * [AmplitudeEnvelope.barsAt] so the rendered MP4 visualises real audio
     * amplitude over time.
     */
    fun renderWaveformBarsOverlay(
        samples: WaveformSamples,
        widthPx: Int = DEFAULT_WIDTH_PX,
        heightPx: Int = DEFAULT_HEIGHT_PX,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Start fully transparent — only the bars carry alpha.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawBars(canvas, samples, widthPx, heightPx)
        return bmp
    }

    /**
     * Editor preview path: composites cover background + bars into one bitmap.
     * Kept for the in-app preview that shows a single still frame; the MP4
     * pipeline now uses the split functions above.
     */
    fun renderWaveformCard(
        samples: WaveformSamples,
        coverArtPath: String?,
        widthPx: Int = DEFAULT_WIDTH_PX,
        heightPx: Int = DEFAULT_HEIGHT_PX,
    ): Bitmap {
        val bmp = renderCoverBackground(coverArtPath, widthPx, heightPx)
        val canvas = Canvas(bmp)
        drawBars(canvas, samples, widthPx, heightPx)
        return bmp
    }

    private fun drawCoverArt(
        canvas: Canvas,
        coverArtPath: String?,
        widthPx: Int,
        heightPx: Int,
    ) {
        val cover =
            coverArtPath?.let {
                runCatching { BitmapFactory.decodeFile(File(it).absolutePath) }.getOrNull()
            } ?: return
        val side = (widthPx * COVER_SIDE_RATIO).toInt()
        val left = (widthPx - side) / 2f
        val top = heightPx * COVER_TOP_RATIO
        canvas.drawBitmap(
            cover,
            null,
            RectF(left, top, left + side, top + side),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        cover.recycle()
    }

    private fun drawBars(
        canvas: Canvas,
        samples: WaveformSamples,
        widthPx: Int,
        heightPx: Int,
    ) {
        val barCount = samples.bars.size
        if (barCount == 0) return
        val cardTop = heightPx * BARS_TOP_RATIO
        val cardBottom = heightPx * BARS_BOTTOM_RATIO
        val cardLeft = widthPx * BARS_LEFT_RATIO
        val cardRight = widthPx * BARS_RIGHT_RATIO
        val barSpacing = (cardRight - cardLeft) / barCount
        val barWidth = barSpacing * BAR_WIDTH_RATIO
        val cardHeight = cardBottom - cardTop
        val barPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(BAR_PINK_HEX)
            }
        val cornerRadius = barWidth / 2f
        for ((i, v) in samples.bars.withIndex()) {
            val h = (cardHeight * v).coerceAtLeast(barWidth)
            val x = cardLeft + i * barSpacing
            val y = cardTop + (cardHeight - h) / 2f
            canvas.drawRoundRect(x, y, x + barWidth, y + h, cornerRadius, cornerRadius, barPaint)
        }
    }

    private const val DEFAULT_WIDTH_PX = 1080
    private const val DEFAULT_HEIGHT_PX = 1920
    private const val DARK_BG_HEX = "#0F0F12"
    private const val BAR_PINK_HEX = "#F472B6"
    private const val COVER_SIDE_RATIO = 0.75f
    private const val COVER_TOP_RATIO = 0.18f
    private const val BARS_TOP_RATIO = 0.62f
    private const val BARS_BOTTOM_RATIO = 0.78f
    private const val BARS_LEFT_RATIO = 0.08f
    private const val BARS_RIGHT_RATIO = 0.92f
    private const val BAR_WIDTH_RATIO = 0.55f
}
