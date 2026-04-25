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
 * cache the UI already populated) and runs AndroidX Palette on it.
 *
 * We extract a single source color from the vibrant family, then derive the second
 * gradient stop in HSV — keeping hue + saturation, but clamping the V channel into a
 * visible band ([MIN_V_BOTTOM]..[MIN_V_TOP]). The brightness floor exists because the
 * tile uses a diagonal `Brush.linearGradient` that places color B exactly at the
 * bottom-right corner; if B drifts near-black (e.g. covers with prominent black
 * elements), that corner becomes indistinguishable from the black inter-tile gutter,
 * and the eye reads it as "the gradient stops short of the edge". Flooring V keeps the
 * corner visibly tinted in the same hue family, so the tile's rounded edge stays
 * clean regardless of source cover.
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
            val source =
                palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                    ?: return@withContext null

            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(source.rgb, it) }
            val h = hsv[0]
            val s = hsv[1]
            val vA = hsv[2].coerceIn(MIN_V_TOP, MAX_V_TOP)
            val vB = (vA * BOTTOM_BRIGHTNESS_RATIO).coerceIn(MIN_V_BOTTOM, vA - MIN_V_DELTA)

            val a = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, vA)))
            val b = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, vB)))
            a to b
        }
    }
}

// Floor for the bright stop. A vibrant swatch typically has V ≥ 0.65; only kicks in
// for unusually dim sources, lifting them so the top of the tile is unambiguously bright.
private const val MIN_V_TOP = 0.60f

// Cap on the bright stop. Very-bright covers (e.g. saturated orange/yellow art with
// V ≈ 1.0) would otherwise leave the tile's text-band too light for white title text.
// Capping at 0.78 keeps the top obviously tinted while pulling the mid-blend (where
// the title sits) into a contrast-safe range.
private const val MAX_V_TOP = 0.78f

// Hard floor for the dark stop. Empirically V ≥ 0.34 keeps the corner visibly tinted
// against the black inter-tile gutter while letting the bottom darken enough to back
// the white title text comfortably.
private const val MIN_V_BOTTOM = 0.34f

// Target brightness ratio between stops when both are unfloored — ≈35% drop top → bottom.
private const val BOTTOM_BRIGHTNESS_RATIO = 0.65f

// Minimum gap between vA and vB. Without this, sources where both stops clamp to floors
// could collapse to a == b and lose the gradient feel.
private const val MIN_V_DELTA = 0.10f
