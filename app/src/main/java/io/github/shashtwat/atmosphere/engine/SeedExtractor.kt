/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 */
package io.github.shashtwat.atmosphere.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.random.Random

/**
 * A photo becomes six coloured seeds: entry 0 is the background colour the photo is pulled toward,
 * entries 1..5 are the blobs. Each seed carries the pixel nearest its swatch in CIE-LAB, which is
 * where that blob starts its drift. Blocking; call off the main thread.
 *
 * Mirrors Nothing OS 5.0's BLUR-ImageLoader: the whole image downscaled by 16 (no crop), a
 * 6-colour Palette with no filters or targets, swatches by population, and a full scan for each
 * swatch's nearest pixel by `ColorUtils` LAB Euclidean distance (first strictly-closer wins).
 */
object SeedExtractor {

    /** No filters, no targets: raw median-cut output, so dark dominant colours stay eligible. */
    private const val MAX_COLOR_COUNT = VertexInfo.SEED_COUNT

    /** Nothing's divisor: `inSampleSize = 16` for files, `createScaledBitmap(w / 16, h / 16)` else. */
    private const val SCAN_DOWNSCALE = 16

    /** Seeds for the image file at [path], decoded with `inSampleSize = 16`. */
    fun extract(path: String): List<VertexInfo>? {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = SCAN_DOWNSCALE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val scan = BitmapFactory.decodeFile(path, options) ?: return null
        try {
            return extractFromScan(scan)
        } finally {
            scan.recycle()
        }
    }

    /** Seeds for [bitmap], scaled by 1/16 with filtering first. [bitmap] is not recycled. */
    fun extract(bitmap: Bitmap): List<VertexInfo> {
        val scan = Bitmap.createScaledBitmap(
            bitmap,
            max(1, bitmap.width / SCAN_DOWNSCALE),
            max(1, bitmap.height / SCAN_DOWNSCALE),
            true,
        )
        try {
            return extractFromScan(scan)
        } finally {
            if (scan !== bitmap) scan.recycle()
        }
    }

    private fun extractFromScan(scan: Bitmap): List<VertexInfo> {
        val swatches = Palette.Builder(scan)
            .maximumColorCount(MAX_COLOR_COUNT)
            .clearFilters()
            .clearTargets()
            .generate()
            .swatches
            .sortedByDescending { it.population }
        if (swatches.isEmpty()) return flatFallback(scan.width, scan.height)

        val seeds = nearestPixelPerSwatch(scan, swatches)
        padToSeedCount(seeds, swatches[0], scan.width, scan.height)
        return seeds
    }

    private fun nearestPixelPerSwatch(bitmap: Bitmap, swatches: List<Palette.Swatch>): MutableList<VertexInfo> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val swatchLab = Array(swatches.size) { DoubleArray(3) }
        for (i in swatches.indices) ColorUtils.colorToLAB(swatches[i].rgb, swatchLab[i])

        val bestDistance = DoubleArray(swatches.size)
        val bestIndex = IntArray(swatches.size) { -1 }
        val pixelLab = DoubleArray(3)

        for (p in pixels.indices) {
            // Nothing reads the pixel as Color.rgb(r, g, b): alpha forced opaque.
            val rgb = pixels[p] or OPAQUE
            ColorUtils.colorToLAB(rgb, pixelLab)
            for (s in swatches.indices) {
                val distance = ColorUtils.distanceEuclidean(swatchLab[s], pixelLab)
                if (bestIndex[s] < 0 || distance < bestDistance[s]) {
                    bestDistance[s] = distance
                    bestIndex[s] = p
                }
            }
        }

        val seeds = mutableListOf<VertexInfo>()
        for (s in swatches.indices) {
            val index = bestIndex[s]
            if (index < 0) continue
            // Nothing derives the coordinate from the buffer position *after* reading the pixel,
            // i.e. index + 1: x is one to the right (wrapping) and the last pixel reports y = h.
            val position = index + 1
            seeds += VertexInfo(
                x = position % width,
                y = position / width,
                bitmapWidth = width,
                bitmapHeight = height,
                pixelColor = pixels[index] or OPAQUE,
                population = swatches[s].population,
            )
        }
        return seeds
    }

    private const val OPAQUE = 0xFF000000.toInt()

    /** Tops up near-monochrome photos with the dominant colour's saturation walked either way. */
    private fun padToSeedCount(seeds: MutableList<VertexInfo>, base: Palette.Swatch, width: Int, height: Int) {
        val missing = VertexInfo.SEED_COUNT - seeds.size
        if (missing <= 0) return
        val half = missing / 2
        val baseHsl = base.hsl.copyOf()

        for (k in half downTo 1) {
            val hsl = baseHsl.copyOf()
            hsl[1] -= k * 0.1f
            if (hsl[1] < 0f) hsl[1] += 0.6f
            seeds += synthesised(ColorUtils.HSLToColor(hsl), width, height)
        }
        for (k in 1..(missing - half)) {
            val hsl = baseHsl.copyOf()
            hsl[1] += k * 0.1f
            if (hsl[1] > 1f) hsl[1] -= 0.6f
            seeds += synthesised(ColorUtils.HSLToColor(hsl), width, height)
        }
    }

    private fun synthesised(rgb: Int, width: Int, height: Int) = VertexInfo(
        x = (Random.nextFloat() * width).toInt().coerceIn(0, width - 1),
        y = (Random.nextFloat() * height).toInt().coerceIn(0, height - 1),
        bitmapWidth = width,
        bitmapHeight = height,
        pixelColor = rgb,
        population = VertexInfo.SYNTHESIZED_POPULATION,
    )

    private fun flatFallback(width: Int, height: Int): List<VertexInfo> = List(VertexInfo.SEED_COUNT) {
        VertexInfo(
            x = 0, y = 0,
            bitmapWidth = width.coerceAtLeast(1),
            bitmapHeight = height.coerceAtLeast(1),
            pixelColor = 0xFF808080.toInt(),
            population = VertexInfo.SYNTHESIZED_POPULATION,
        )
    }
}
