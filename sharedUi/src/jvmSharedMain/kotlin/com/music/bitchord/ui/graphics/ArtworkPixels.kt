package com.music.bitchord.ui.graphics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.size.Scale
import coil3.Image
import coil3.request.ImageRequest

/**
 * Pixel access to artwork, for the backdrops and palettes that read covers back
 * colour by colour. Compose reads pixels on every platform
 * ([ImageBitmap.readPixels]); what differs is getting a decoded Coil image into
 * an [ImageBitmap] and building one back out of pixels.
 */

/** Asks Coil for a bitmap the CPU can read — a software bitmap on Android. */
expect fun ImageRequest.Builder.forPixelAccess(): ImageRequest.Builder

/** Compact RAM-saving decode for opaque list/grid thumbnails (RGB_565 on Android, no crossfade flash). */
expect fun ImageRequest.Builder.forCompactThumbnail(): ImageRequest.Builder

/** The decoded image as a Compose bitmap. */
expect fun Image.toImageBitmap(): ImageBitmap

/** An ARGB_8888 bitmap of [width] x [height] from packed ARGB [pixels]. */
expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** Every pixel of [image], packed ARGB, row by row. */
fun ImageBitmap.argbPixels(): IntArray =
    IntArray(width * height).also { readPixels(it) }

/** One colour the palette found, and how much of the image it covers. */
class Swatch(val rgb: Int, val population: Int)

/**
 * The colours androidx Palette would find in [image]: up to [maxColors]
 * swatches, with its default filter (near-black, near-white, the red I-line)
 * unless [clearFilters].
 */
expect fun paletteSwatches(image: ImageBitmap, maxColors: Int, clearFilters: Boolean): List<Swatch>


internal fun browseArtworkRequest(
    context: PlatformContext,
    url: String,
    width: Int,
    height: Int = width,
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size(width, height)
    .scale(Scale.FILL)
    .build()

@Composable
internal fun rememberBrowseArtworkRequest(url: String?, width: Int, height: Int = width): ImageRequest? {
    val context = LocalPlatformContext.current
    return remember(context, url, width, height) {
        url?.let { browseArtworkRequest(context, it, width, height) }
    }
}
