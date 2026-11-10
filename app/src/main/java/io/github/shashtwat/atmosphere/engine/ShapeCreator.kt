/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 */
package io.github.shashtwat.atmosphere.engine

import android.opengl.GLES30
import android.opengl.Matrix
import java.util.Random

/**
 * The blob layer: five flat-filled shapes that appear at frame
 * [AtmosphereConstants.FIRST_BLOB_FRAME], grow, and drift across the screen.
 *
 * Each blob **starts where its color lives in the photo** and travels to a random target, so the
 * opening frames of a morph correlate with the source image and the settled frame does not. The
 * targets are re-rolled on every lock, which is why the same wallpaper never settles the same way
 * twice.
 */
internal class ShapeCreator {

    private companion object {
        /**
         * Rolled **once per process** and only advanced thereafter. This is not the same lifetime
         * as `targetRandom` and the difference shows: blob sizes and the path shape evolve slowly
         * across the process's life, while the layout jumps on every lock.
         */
        val PROCESS_RANDOM = Random(System.currentTimeMillis() / 1000L)

        const val FLOATS_PER_POSITION = 2
    }

    private val outlines = Array(AtmosphereConstants.BLOB_COUNT) { BlobOutline() }

    /** Drift destinations in surface pixels, five (x, y) pairs. */
    private val targets = FloatArray(AtmosphereConstants.BLOB_COUNT * 2)

    /** Per blob, per anchor, quantized to {2, 4, 6}. */
    private val steps = Array(AtmosphereConstants.BLOB_COUNT) {
        FloatArray(AtmosphereConstants.ANCHOR_COUNT)
    }

    /** The width-scaled pentagon every outline is rewound to; rebuilt by [fillBaseAnchors]. */
    private val baseAnchors = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** 0 straight, 1 and 2 the two semicircular arcs. Shared by all five blobs, not per blob. */
    private var rotationMode = 0

    private var seeds: List<VertexInfo> = emptyList()
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var program = 0
    private var uMvpMatrix = 0
    private var uAlpha = 0
    private var uColor = 0
    private var vbo = 0

    private val positions = FloatArray(BlobOutline.MAX_STRIP_VERTICES * FLOATS_PER_POSITION)
    private val positionBuffer = AtmosphereGl.newFloatBuffer(positions.size)

    private val mvp = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)

    private val positionBytes = positions.size * AtmosphereGl.BYTES_PER_FLOAT

    val isReady: Boolean get() = program != 0 && seeds.size == VertexInfo.SEED_COUNT

    // Lifecycle

    fun onSurfaceCreated(vertexSource: String, fragmentSource: String) {
        program = AtmosphereGl.buildProgram(vertexSource, fragmentSource, "blob")
        if (program == 0) return
        uMvpMatrix = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uColor = GLES30.glGetUniformLocation(program, "uColor")

        // Sized once at the worst case and only ever sub-loaded after this, so the per-frame path
        // never re-specifies buffer storage — five blobs a frame is not the place to reallocate.
        vbo = AtmosphereGl.createBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, positionBytes, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        AtmosphereGl.deleteBuffer(vbo)
        vbo = 0
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    // State

    fun setSeeds(newSeeds: List<VertexInfo>) {
        seeds = newSeeds
    }

    /**
     * Re-rolls everything that is random and rewinds every outline to its base pentagon.
     *
     * Called on entering the lock state, which is what makes each unlock a fresh layout.
     */
    fun reset(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (seeds.size != VertexInfo.SEED_COUNT) return

        // Re-seeded from the wall clock on every call, unlike PROCESS_RANDOM.
        val targetRandom = Random(System.currentTimeMillis() / 1000L)
        BlobPlacement.generate(width, height, AtmosphereConstants.BLOB_COUNT, targetRandom, targets)

        rotationMode = PROCESS_RANDOM.nextInt(3)

        val rate = AtmosphereConstants.renderRate(width)
        // One pentagon for all five
        fillBaseAnchors(rate)
        for (s in 0 until AtmosphereConstants.BLOB_COUNT) {
            for (j in 0 until AtmosphereConstants.ANCHOR_COUNT) {
                steps[s][j] = ((PROCESS_RANDOM.nextInt(3) * 2 + 2).toFloat()) * rate
            }
            // Seed 0 is the background color; the blobs are 1..5.
            outlines[s].reset(baseAnchors, steps[s], seeds[s + 1].pixelColor)
        }
    }

    /**
     * The base pentagon, scaled about its centroid C by the type-0 (linear) render rate. On a
     * panel at least 1080 wide this is the literal constant.
     */
    private fun fillBaseAnchors(rate: Float) {
        for (i in 0 until AtmosphereConstants.ANCHOR_COUNT) {
            val dx = AtmosphereConstants.DEFAULT_VERTICES[i * 2] - AtmosphereConstants.DEFAULT_CENTROID_X
            val dy = AtmosphereConstants.DEFAULT_VERTICES[i * 2 + 1] - AtmosphereConstants.DEFAULT_CENTROID_Y
            baseAnchors[i * 2] = AtmosphereConstants.DEFAULT_CENTROID_X + dx * rate
            baseAnchors[i * 2 + 1] = AtmosphereConstants.DEFAULT_CENTROID_Y + dy * rate
        }
    }

    // Drawing

    /**
     * Blends the five blobs over whatever is already in the bound framebuffer.
     *
     * Returns without touching GL below [AtmosphereConstants.FIRST_BLOB_FRAME] -- for the first
     * ten frames the blobs simply do not exist.
     *
     * @param isInit true when seeking, which replays each outline's growth from its pristine
     * anchors rather than stepping it.
     */
    fun draw(frame: Int, isInit: Boolean, width: Int, height: Int) {
        if (frame < AtmosphereConstants.FIRST_BLOB_FRAME) return
        if (!isReady) return
        if (width != surfaceWidth || height != surfaceHeight) return

        val eased = AtmosphereConstants.ANIM_EASE.getInterpolation(travelProgress(frame))

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uAlpha, if (isInit) 1f else alphaAt(frame))

        Matrix.orthoM(projection, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, FLOATS_PER_POSITION, GLES30.GL_FLOAT, false, 0, 0)

        for (s in 0 until AtmosphereConstants.BLOB_COUNT) {
            drawBlob(s, frame, isInit, eased, width, height)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawBlob(
        index: Int,
        frame: Int,
        isInit: Boolean,
        easedTravel: Float,
        width: Int,
        height: Int
    ) {
        val outline = outlines[index]
        outline.advanceTo(frame, isInit)

        val vertexCount = outline.tessellate(frame, positions)

        positionBuffer.position(0)
        positionBuffer.put(positions, 0, vertexCount * FLOATS_PER_POSITION)
        positionBuffer.position(0)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0,
            vertexCount * FLOATS_PER_POSITION * AtmosphereGl.BYTES_PER_FLOAT, positionBuffer
        )

        // Constant across the strip and only changes on reset, so a uniform rather than a
        // per-vertex attribute streamed five times a frame.
        GLES30.glUniform3fv(uColor, 1, outline.color, 0)
        applyMvp(index, easedTravel, width, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, vertexCount)
        // Nothing steps the tension after drawing, so frame 10 draws with straight handles.
        outline.stepTension()
    }

    /**
     * Places the blob on screen exactly as Nothing's model matrix does (ShapeCreator::draw,
     * libblurwallpaper 0x28678-0x28a28).
     *
     * The outline's vertices sit around the base centroid C in pixel space. Nothing builds
     * `translate(-C)` once, then `translate(T)` on top, so the model is `translate(T - C)` and the
     * blob's centroid starts exactly on its seed pixel T (`T = (x / bw * W, (1 - y / bh) * H)`),
     * then travels to the target R:
     * - mode 0: `translate(T - C + (R - T) * p)`
     * - modes 1/2: `translate(mid) * rotateZ(-+PI * p) * translate(T - C - mid)`, mid = (T + R) / 2;
     *   the whole outline turns while it orbits.
     */
    private fun applyMvp(index: Int, easedTravel: Float, width: Int, height: Int) {
        val seed = seeds[index + 1]
        val tx = (seed.x.toFloat() / seed.bitmapWidth) * width
        // Y is flipped into GL's origin: the photo's top row is the screen's top.
        val ty = (1f - seed.y.toFloat() / seed.bitmapHeight) * height
        val cx = AtmosphereConstants.DEFAULT_CENTROID_X
        val cy = AtmosphereConstants.DEFAULT_CENTROID_Y
        val targetX = targets[index * 2]
        val targetY = targets[index * 2 + 1]

        Matrix.setIdentityM(model, 0)
        if (rotationMode == 0) {
            Matrix.translateM(
                model, 0,
                tx - cx + (targetX - tx) * easedTravel,
                ty - cy + (targetY - ty) * easedTravel,
                0f
            )
        } else {
            val midX = (tx + targetX) * 0.5f
            val midY = (ty + targetY) * 0.5f
            val sweep = if (rotationMode == 1) -180f else 180f
            Matrix.translateM(model, 0, midX, midY, 0f)
            Matrix.rotateM(model, 0, sweep * easedTravel, 0f, 0f, 1f)
            Matrix.translateM(model, 0, tx - cx - midX, ty - cy - midY, 0f)
        }

        // multiplyMM only forbids aliasing between the result and its inputs, and mvp is neither.
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)
        GLES30.glUniformMatrix4fv(uMvpMatrix, 1, false, mvp, 0)
    }

    /** `min(frame - 10, 175) / 175`; the ease is applied by the caller. */
    private fun travelProgress(frame: Int): Float {
        val span = (AtmosphereConstants.TOTAL_ANIM_FRAMES - AtmosphereConstants.FIRST_BLOB_FRAME).toFloat()
        return minOf((frame - AtmosphereConstants.FIRST_BLOB_FRAME).toFloat(), span) / span
    }

    /**
     * Blob opacity: `ease((f - 10) / 55)` up to frame
     * [AtmosphereConstants.RANDOMIZE_SHAPES_TRANSITION], 1.0 after; a seek always draws at 1.0.
     */
    private fun alphaAt(frame: Int): Float = AtmosphereConstants.easedRamp(
        frame,
        from = AtmosphereConstants.FIRST_BLOB_FRAME,
        to = AtmosphereConstants.RANDOMIZE_SHAPES_TRANSITION
    )
}
