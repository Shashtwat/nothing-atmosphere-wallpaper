/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 */
package io.github.shashtwat.atmosphere.engine

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Every tuning value the effect runs on.
 *
 * `animRatio` is 1.0 in the only shipped configuration, so each milestone below is its literal
 * multiplier. They are kept as the *products* rather than as `ratio * k` because there is exactly
 * one call site and no preset table (the effect has a single configuration).
 *
 * All absolute distances are in **1080-wide panel space**; see [renderRate].
 */
internal object AtmosphereConstants {

    // Frame milestones

    /** `uAlpha` reaches 1.0 here — barely a third into the morph. */
    const val RANDOMIZE_SHAPES_TRANSITION = 65

    /** Last frame on which a blob's outline grows. */
    const val POLYGON_CHANGE_END_FRAME = 100

    /** End of the morph. The settled state is a seek to [SETTLED_FRAME]. */
    const val TOTAL_ANIM_FRAMES = 185

    /** `uMixBlend` reaches 1.0 here — the photo is fully replaced by the background color. */
    const val BG_TEXTURE_TRANSITION = 135

    /** Blur ramp end. Also, via `* 0.1f`, the frame the render target collapses to 72 px. */
    const val BG_BLUR_GROWTH = 100

    /** Grain ramp end. The only window with a linear curve. */
    const val NOISE_GROWTH = 80

    /**
     * One past [TOTAL_ANIM_FRAMES]. Seeking here is how the engine draws its settled state
     * whenever it is not animating.
     */
    const val SETTLED_FRAME = TOTAL_ANIM_FRAMES + 1

    /**
     * Nothing blob-related is drawn below this frame, the render target is still half-resolution,
     * and the growth loop's one-off 30x kick lands exactly on it. Three separate behaviors share
     * this boundary.
     */
    const val FIRST_BLOB_FRAME = 10

    // Magnitudes

    /** Gaussian radius at full ramp. Clamped to [BLUR_RADIUS_LIMIT] upstream; 30 is well under. */
    const val MAX_BLUR_RADIUS = 30

    /** Per-tap step of the separable blur at full ramp, in 1080-panel pixels */
    const val MAX_BLUR_OFFSET = 14f

    /** Grain mix at full ramp. */
    const val MAX_NOISE = 0.14f

    /** The blur shader's `uKernel` is declared at this size and always uploaded full. */
    const val BLUR_RADIUS_LIMIT = 124

    // Shape scale (ANIM_LEVEL 0; level 1 would be 0.8 / 0.05)

    /**
     * Bezier handle tension guard. The outline is drawn first and only then
     * `if (scale < 0.7f) scale += 0.044f`, so frame 10 draws at 0 and the value parks at 0.704.
     */
    const val CURVE_SCALE_MAX = 0.7f

    /** Per-frame tension step, applied after each draw while below [CURVE_SCALE_MAX]. */
    const val CURVE_SCALE_STEP = 0.044f

    // Geometry

    /** Five blobs, five anchors each. Both are structural, not tunable. Entry 0 of SEED_COUNT is the background*/
    const val BLOB_COUNT = VertexInfo.SEED_COUNT - 1
    const val ANCHOR_COUNT = 5

    /**
     * The base outline every blob starts from, in **surface pixels, y up**, exactly as Nothing
     * ships it: a small jittered pentagon around (339, 540). The model is `translate(T - C)`, so the
     * outline is moved to put this centroid on the blob's seed pixel T.
     */
    val DEFAULT_VERTICES = floatArrayOf(
        334f, 550f,
        330f, 537f,
        338f, 532f,
        349f, 535f,
        343f, 546f
    )

    /** Shoelace centroid of [DEFAULT_VERTICES]; growth radiates from here and the fill fans from it. */
    const val DEFAULT_CENTROID_X = 338.71884f
    const val DEFAULT_CENTROID_Y = 540.05786f

    /** Minimum spacing between blob targets, in **surface pixels**. */
    const val MIN_TARGET_SEPARATION = 500f

    /** Rejection-sampling budget before a candidate is accepted regardless. */
    const val PLACEMENT_MAX_ATTEMPTS = 100

    /** The one-off multiplier applied to every anchor's growth step on [FIRST_BLOB_FRAME]. */
    const val GROWTH_KICK = 30f

    /**
     * Segments per Bezier span are `max(3, trunc((1 - frame / 100) * 16))`, and each span emits
     * `segments + 1` points, so the outline coarsens as the morph runs.
     */
    const val MAX_SEGMENTS_PER_SPAN = 16
    const val MIN_SEGMENTS_PER_SPAN = 3

    // Glass (Nothing's glass fragment shader)

    /** Ribs across one screen width; the shader's `size` is this scaled to the displayed image. */
    const val GLASS_RIBS_PER_SCREEN = 32
    const val GLASS_X_FACTOR_A = 0.313f
    const val GLASS_X_FACTOR_B = 0.202f
    const val GLASS_X_FACTOR_C = 0.03f

    // Render targets

    /**
     * Width of the buffer the entire effect is composited, rasterized and blurred in from
     * [FIRST_BLOB_FRAME] onward. The height follows the surface aspect.
     *
     * This is load-bearing, not a performance shortcut: sigma = radius/3 = 10 texels here is a
     * blur ~14% of the frame width, and reproducing that at half resolution would need a radius
     * beyond [BLUR_RADIUS_LIMIT]. The bilinear magnify back to the panel is what turns five
     * flat-filled polygons into smooth gradients.
     */
    const val EFFECT_RASTER_WIDTH = 72

    /** Divisor for the render target used *below* [FIRST_BLOB_FRAME]. */
    const val PREROLL_DOWNSCALE = 2

    // Clock

    /**
     * The renderer self-clocks to this floor and re-arms itself with `requestRender()`, so the
     * morph lasts ~2.96 s on any panel
     */
    const val FRAME_INTERVAL_MS = 16L

    /** Frames the replaying morph holds on the settled effect before rewinding to the photo ~0.75 s at [FRAME_INTERVAL_MS]. */
    const val LOOP_DWELL_FRAMES = 45

    /** Frames the replay holds the untouched photo before each morph. */
    const val LOOP_PHOTO_HOLD_FRAMES = 45

    /** Reference panel width. Above this, [renderRate] is identically 1.0. */
    const val REFERENCE_WIDTH = 1080f

    // Easing

    /**
     * The animation curve, shared by every eased window (alpha, mix, travel, blur).
     * A strong ease-out: most of the change lands early.
     */
    val ANIM_EASE: Interpolator = NothingEase(0.15f, 0f, 0f, 1f)

    /** Nothing's `renderRate(width, type = 1)` curve. Used by the blur alone. */
    private val RENDER_RATE_EASE: Interpolator = NothingEase(0f, 0f, 0.5f, 1f)

    /**
     * Nothing's `renderRate(width, type = 0)`: resolution adaptation against [REFERENCE_WIDTH].
     * 1.0 on any panel at least that wide, linear below it. Scales the base pentagon and the
     * per-anchor growth steps (blob size and growth).
     */
    fun renderRate(width: Int): Float {
        if (width > REFERENCE_WIDTH - 1f) return 1f
        return width / REFERENCE_WIDTH
    }

    /**
     * Nothing's `renderRate(width, type = 1)`: the same adaptation through [RENDER_RATE_EASE].
     * Scales the blur radius and step. The grain uses neither rate (constant 1.0).
     */
    fun easedRenderRate(width: Int): Float {
        if (width > REFERENCE_WIDTH - 1f) return 1f
        return RENDER_RATE_EASE.getInterpolation(width / REFERENCE_WIDTH)
    }

    // Frame ramps

    /** Linear 0..1 progress of [frame] across the window `[from, to]`, clamped at both ends. */
    fun ramp(frame: Int, from: Int = 0, to: Int): Float =
        ((frame - from).toFloat() / (to - from)).coerceIn(0f, 1f)

    /** [ramp] through [ANIM_EASE], which is what every window but the grain uses. */
    fun easedRamp(frame: Int, from: Int = 0, to: Int): Float =
        ANIM_EASE.getInterpolation(ramp(frame, from, to))
}
