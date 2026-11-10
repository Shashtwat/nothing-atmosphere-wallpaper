/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 * Reworked to match Nothing OS 5.0's libblurwallpaper.so BezierCurve exactly.
 */
package io.github.shashtwat.atmosphere.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One blob's outline: five anchors that push outward every frame, smoothed into a closed cubic
 * Bezier and emitted as a triangle strip that fans from the base centroid.
 *
 * The anchors live in the base pentagon's own pixel space around
 * ([AtmosphereConstants.DEFAULT_CENTROID_X], [AtmosphereConstants.DEFAULT_CENTROID_Y]); placement
 * is entirely the caller's model matrix.
 *
 * Mirrors Nothing's `BezierCurve::drawInstance` / `drawBezierCurve`, including the integer
 * truncation of the handles, the frame-driven tessellation density and the draw-then-step
 * tension update.
 */
internal class BlobOutline {

    /** Working anchors, five (x, y) pairs, mutated every frame. */
    private val anchors = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** Untouched copy of the base pentagon; a seek restores from this. */
    private val pristine = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** Per-anchor radial growth rate, quantized to {2, 4, 6} and scaled by the render rate. */
    private val steps = FloatArray(AtmosphereConstants.ANCHOR_COUNT)

    /** Handle tension. Drawn first, then stepped while below [AtmosphereConstants.CURVE_SCALE_MAX]. */
    private var curveScale = 0f

    /** Flat fill color, `pixelColor` of this blob's seed, unpacked to 0..1. */
    val color = FloatArray(3)

    /** Edge midpoints, `mid[i] = (p[i] + p[i+1]) / 2`. */
    private val mids = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** Handles: `[2i]` is anchor i's in-handle, `[2i+1]` its out-handle, (x, y) each. */
    private val handles = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 4)

    /** The tessellated loop before it is woven into a strip. Held: this runs five times a frame. */
    private val loop = FloatArray(MAX_LOOP_POINTS * 2)

    fun reset(baseAnchors: FloatArray, growthSteps: FloatArray, blobColor: Int) {
        baseAnchors.copyInto(pristine)
        baseAnchors.copyInto(anchors)
        growthSteps.copyInto(steps)
        curveScale = 0f
        AtmosphereGl.unpackRgb(blobColor, color)
    }

    /**
     * Brings the outline to [frame], as `drawInstance` does before drawing.
     *
     * Seeking ([isInit]) restores the pristine anchors, steps the tension once per frame up to
     * [frame] (from its current value, while below the cap), and replays every growth push from
     * [AtmosphereConstants.FIRST_BLOB_FRAME] to `min(100, frame)`. Otherwise a single push for
     * this frame while it is below [AtmosphereConstants.POLYGON_CHANGE_END_FRAME].
     */
    fun advanceTo(frame: Int, isInit: Boolean) {
        val end = AtmosphereConstants.POLYGON_CHANGE_END_FRAME
        if (end <= frame && !isInit) return

        val from: Int
        val stop: Int
        if (isInit) {
            pristine.copyInto(anchors)
            for (k in 0 until frame) {
                if (curveScale < AtmosphereConstants.CURVE_SCALE_MAX) {
                    curveScale += AtmosphereConstants.CURVE_SCALE_STEP
                } else {
                    break
                }
            }
            from = AtmosphereConstants.FIRST_BLOB_FRAME
            stop = minOf(end, frame)
        } else {
            from = frame
            stop = frame + 1
        }
        for (k in from until stop) push(k)
    }

    /** Called once after each draw: `if (scale < 0.7f) scale += 0.044f`. */
    fun stepTension() {
        if (curveScale < AtmosphereConstants.CURVE_SCALE_MAX) {
            curveScale += AtmosphereConstants.CURVE_SCALE_STEP
        }
    }

    /**
     * One frame of radial growth from the base centroid. The push on
     * [AtmosphereConstants.FIRST_BLOB_FRAME] is multiplied by [AtmosphereConstants.GROWTH_KICK].
     */
    private fun push(k: Int) {
        val cx = AtmosphereConstants.DEFAULT_CENTROID_X
        val cy = AtmosphereConstants.DEFAULT_CENTROID_Y
        for (j in 0 until AtmosphereConstants.ANCHOR_COUNT) {
            var d = steps[j]
            if (k == AtmosphereConstants.FIRST_BLOB_FRAME) d *= AtmosphereConstants.GROWTH_KICK
            val dx = anchors[j * 2] - cx
            val dy = anchors[j * 2 + 1] - cy
            val inv = 1f / sqrt(dx * dx + dy * dy)
            anchors[j * 2] += dx * inv * d
            anchors[j * 2 + 1] += dy * inv * d
        }
    }

    /**
     * Tessellates the closed outline for [frame] and writes it into [out] as a triangle strip,
     * returning the vertex count.
     *
     * Handles follow Nothing: `shift = int(p[i] - (mid[i-1] + mid[i]) / 2)`,
     * `in = p + int((mid[i-1] + shift - p) * scale)`, `out = p + int((mid[i] + shift - p) * scale)`.
     * Each span emits `segments + 1` points with `segments = max(3, int((1 - frame / 100) * 16))`.
     * The strip is `C, p0, p1, C, p2, p3, ...` — effectively a fan from the base centroid C.
     */
    fun tessellate(frame: Int, out: FloatArray): Int {
        val n = AtmosphereConstants.ANCHOR_COUNT

        for (i in 0 until n) {
            val next = if (i == n - 1) 0 else i + 1
            mids[i * 2] = (anchors[i * 2] + anchors[next * 2]) * 0.5f
            mids[i * 2 + 1] = (anchors[i * 2 + 1] + anchors[next * 2 + 1]) * 0.5f
        }

        val scale = curveScale
        for (i in 0 until n) {
            val prev = (n - 1 + i) % n
            for (c in 0..1) {
                val p = anchors[i * 2 + c]
                val mPrev = mids[prev * 2 + c]
                val mCur = mids[i * 2 + c]
                val shift = (p + (mCur + mPrev) * -0.5f).toInt().toFloat()
                val inH = p + ((mPrev + shift - p) * scale).toInt().toFloat()
                val outH = p + ((mCur + shift - p) * scale).toInt().toFloat()
                handles[(2 * i) * 2 + c] = inH
                handles[((2 * i + 1) % (2 * n)) * 2 + c] = outH
            }
        }

        val raw = ((frame / -100f) + 1f) * AtmosphereConstants.MAX_SEGMENTS_PER_SPAN
        val segments = maxOf(raw.toInt(), AtmosphereConstants.MIN_SEGMENTS_PER_SPAN)
            .coerceAtMost(AtmosphereConstants.MAX_SEGMENTS_PER_SPAN)
        val segmentsF = segments.toFloat()

        var w = 0
        for (i in 0 until n) {
            val next = if (i + 1 == n) 0 else i + 1
            val p0x = anchors[i * 2]
            val p0y = anchors[i * 2 + 1]
            val p3x = anchors[next * 2]
            val p3y = anchors[next * 2 + 1]
            val h1 = (2 * i + 1) * 2
            val h2 = ((2 * i + 2) % (2 * n)) * 2
            val c1x = handles[h1]
            val c1y = handles[h1 + 1]
            val c2x = handles[h2]
            val c2y = handles[h2 + 1]

            for (j in 0..segments) {
                val t = j / segmentsF
                val u = 1f - t
                val b0 = u.pow(3)
                val b1 = t * (u * u * 3f)
                val b2 = (t * t) * (u * 3f)
                val b3 = t.pow(3)
                loop[w++] = p3x * b3 + ((c2x * b2) + ((p0x * b0) + (c1x * b1)))
                loop[w++] = p3y * b3 + ((c2y * b2) + ((p0y * b0) + (c1y * b1)))
            }
        }

        return emitStrip(w / 2, out)
    }

    /** `C, p0, p1, C, p2, p3, ...`: the centroid before every even-indexed point. */
    private fun emitStrip(pointCount: Int, out: FloatArray): Int {
        var w = 0
        for (k in 0 until pointCount) {
            if (k and 1 == 0) {
                out[w++] = AtmosphereConstants.DEFAULT_CENTROID_X
                out[w++] = AtmosphereConstants.DEFAULT_CENTROID_Y
            }
            out[w++] = loop[k * 2]
            out[w++] = loop[k * 2 + 1]
        }
        return w / 2
    }

    companion object {
        private const val MAX_LOOP_POINTS =
            AtmosphereConstants.ANCHOR_COUNT * (AtmosphereConstants.MAX_SEGMENTS_PER_SPAN + 1)

        /** Upper bound on [tessellate]'s vertex count, for one-time buffer sizing. */
        const val MAX_STRIP_VERTICES = MAX_LOOP_POINTS + (MAX_LOOP_POINTS + 1) / 2
    }
}
