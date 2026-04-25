// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.palette

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the cover image via Coil's singleton loader (so we share the in-memory + disk
 * cache the UI already populated) and runs AndroidX Palette on it. Picks vibrant for
 * color A and dark-muted for color B; both fall back through the swatch hierarchy and
 * default to a neutral pair only as a last resort — the [PaletteCache] will short-circuit
 * to the seeded fallback when this returns null.
 */
class AndroidPalettePort(private val context: Context) : PalettePort {
    override suspend fun extract(model: Any?): Pair<Color, Color>? {
        if (model == null) return null
        val loader = SingletonImageLoader.get(context)
        val request =
            ImageRequest
                .Builder(context)
                .data(model)
                .allowHardware(false)
                .build()
        val result = loader.execute(request)
        if (result !is SuccessResult) return null
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: return null

        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).generate()
            val a = palette.vibrantSwatch ?: palette.dominantSwatch ?: return@withContext null
            val b =
                palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                    ?: a
            opaque(a.rgb) to opaque(b.rgb)
        }
    }
}

/** Palette's Swatch.rgb returns RGB without alpha; force opaque so the Compose Color isn't transparent. */
private fun opaque(argb: Int): Color = Color(argb or OPAQUE_MASK)

private const val OPAQUE_MASK = 0xFF000000.toInt()
