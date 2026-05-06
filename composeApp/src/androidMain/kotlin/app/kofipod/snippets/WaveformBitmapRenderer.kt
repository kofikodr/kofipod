// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/**
 * Renders the cover-card frame used as the MP4's video track: cover art
 * (centred + cropped) with a waveform card overlaid in the lower third.
 *
 * Pre-rendered to a Bitmap once per snippet then handed to Transformer as the
 * video source — Transformer loops it across the clip duration so the final
 * MP4 has a static cover-card background with audio.
 *
 * Android-only because [Bitmap] and [Canvas] are Android platform types.
 */
internal object WaveformBitmapRenderer {
    /**
     * @param samples       Waveform amplitude bars in `[0f, 1f]` — same samples
     *                      the editor waveform widget uses so they always match.
     * @param coverArtPath  Absolute local file path to the episode artwork, or
     *                      null if unavailable. Remote URLs are NOT supported
     *                      here (callers must pass a local path or null).
     * @param widthPx       Output bitmap width in pixels. Defaults to 1080 (portrait 9:16).
     * @param heightPx      Output bitmap height in pixels. Defaults to 1920.
     */
    fun renderWaveformCard(
        samples: WaveformSamples,
        coverArtPath: String?,
        widthPx: Int = 1080,
        heightPx: Int = 1920,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Dark background matching the app's c.bg token (#0F0F12).
        canvas.drawColor(Color.parseColor("#0F0F12"))

        // 1. Cover art — decode from local path and draw centred into the upper
        //    ~55% of the frame, cropped into a square.
        val cover =
            coverArtPath?.let {
                runCatching { BitmapFactory.decodeFile(File(it).absolutePath) }.getOrNull()
            }
        if (cover != null) {
            val side = (widthPx * 0.75f).toInt()
            val left = (widthPx - side) / 2f
            val top = heightPx * 0.18f
            canvas.drawBitmap(
                cover,
                null,
                RectF(left, top, left + side, top + side),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            cover.recycle()
        }

        // 2. Waveform card — pink bars across the lower third (~62%–78% of height).
        //    Each bar is centred vertically within the card lane.
        val barCount = samples.bars.size
        if (barCount == 0) return bmp // Guard: no bars → return dark bg only.

        val cardTop = heightPx * 0.62f
        val cardBottom = heightPx * 0.78f
        val cardLeft = widthPx * 0.08f
        val cardRight = widthPx * 0.92f
        val barSpacing = (cardRight - cardLeft) / barCount
        val barWidth = barSpacing * 0.55f
        val cardHeight = cardBottom - cardTop
        val barPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Snippet waveform pink — matches the c.accentPink token.
                color = Color.parseColor("#F472B6")
            }
        val cornerRadius = barWidth / 2f
        for ((i, v) in samples.bars.withIndex()) {
            // Minimum bar height = 1 bar-width so zero-amplitude bars are still visible.
            val h = (cardHeight * v).coerceAtLeast(barWidth)
            val x = cardLeft + i * barSpacing
            val y = cardTop + (cardHeight - h) / 2f
            canvas.drawRoundRect(x, y, x + barWidth, y + h, cornerRadius, cornerRadius, barPaint)
        }

        return bmp
    }
}
