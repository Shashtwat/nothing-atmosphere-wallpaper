/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 * Simplified for a single wallpaper and extended with the Glass effect.
 */
package io.github.shashtwat.atmosphere.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The pipeline: photo -> blobs over it -> blur both together -> grain, magnified to the panel.
 *
 * Locked, this draws the plain photo (with Glass if on). On unlock it plays the 185-frame morph
 * (~2.96 s) and then holds the settled frame without redrawing.
 */
class AtmosphereRenderer(
    private val context: Context,
    private val requestRender: () -> Unit,
    /** GL thread: there is no source loaded for this context, ask the host for one. */
    private val onSourceMissing: () -> Unit = {},
) : GLSurfaceView.Renderer {

    private companion object {
        const val TAG = "AtmosphereRenderer"
        const val SHADER_DIR = "shaders/atmosphere"
        const val SCREEN_ASPECT_TOLERANCE = 0.01f
    }

    private class Targets {
        // Nothing's chain (drawFBO): composite -> A, horizontal blur -> B, vertical blur -> C, grain
        // reads C. B and C are never cleared: the blurs blend over the previous frame's result.
        var texA = 0
        var texB = 0
        var texC = 0
        var width = 0
        var height = 0

        fun ensure(w: Int, h: Int) {
            if (width == w && height == h && texA != 0) return
            release()
            width = w
            height = h
            texA = AtmosphereGl.createFboTexture(w, h)
            texB = AtmosphereGl.createFboTexture(w, h)
            texC = AtmosphereGl.createFboTexture(w, h)
        }

        fun release() {
            AtmosphereGl.deleteTexture(texA)
            AtmosphereGl.deleteTexture(texB)
            AtmosphereGl.deleteTexture(texC)
            forget()
        }

        fun forget() {
            texA = 0; texB = 0; texC = 0; width = 0; height = 0
        }
    }

    // Hand-off from other threads

    private val pendingSource = AtomicReference<Pair<List<VertexInfo>, Bitmap>?>(null)
    private val locked = AtomicBoolean(true)
    private val lockStateDirty = AtomicBoolean(false)
    private val animateUnlock = AtomicBoolean(true)
    private val settleRequested = AtomicBoolean(false)
    private val loopMorph = AtomicBoolean(false)
    private val loopRestart = AtomicBoolean(false)

    @Volatile private var effectEnabled = true
    @Volatile private var glassEnabled = false
    @Volatile private var ribCount = AtmosphereConstants.GLASS_RIBS_PER_SCREEN

    // GL objects

    private var compositeProgram = 0
    private var blurProgram = 0
    private var grainProgram = 0

    private var uBgColor = 0
    private var uMixBlend = 0
    private var uCoverScale = 0
    private var uGlass = 0
    private var uGlassSize = 0
    private var uXFactorA = 0
    private var uXFactorB = 0
    private var uXFactorC = 0

    private var uKernel = 0
    private var uBlurRadius = 0
    private var uBlurOffset = 0

    private var uNoiseGrowth = 0

    private var fbo = 0
    private var photoTexture = 0
    private var photoWidth = 0
    private var photoHeight = 0

    private val quad = AtmosphereGl.FullScreenQuad()
    private val prerollTargets = Targets()
    private val effectTargets = Targets()
    private val shapes = ShapeCreator()

    // Animation state (GL thread)

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    /** Nothing's renderRate(W, type 1). The blur is the only consumer. */
    private var easedRenderRate = 1f
    private var frame = AtmosphereConstants.SETTLED_FRAME
    private var animating = false
    private var lastFrameAt = 0L
    private var loopDwell = 0
    private var loopPhotoHold = 0

    private var seeds: List<VertexInfo> = emptyList()
    private val bgColor = FloatArray(3)
    private val coverScale = FloatArray(2)
    private val kernel = FloatArray(AtmosphereConstants.BLUR_RADIUS_LIMIT)
    private var uploadedKernelRadius = -1

    // External control

    /** Hands over a new photo and its seeds; adopted on the next frame. Takes ownership of [bitmap]. */
    fun queueSource(newSeeds: List<VertexInfo>, bitmap: Bitmap) {
        pendingSource.getAndSet(newSeeds to bitmap)?.second?.recycle()
        requestRender()
    }

    fun setOptions(atmosphere: Boolean, glass: Boolean, ribs: Int) {
        effectEnabled = atmosphere
        glassEnabled = glass
        ribCount = ribs
        requestRender()
    }

    /** true shows the plain photo, false the effect. [animate] decides whether a false edge morphs. */
    fun setLocked(isLocked: Boolean, animate: Boolean = true) {
        if (locked.getAndSet(isLocked) != isLocked) {
            animateUnlock.set(animate)
            lockStateDirty.set(true)
            requestRender()
        }
    }

    /** Abandons any morph in flight and jumps to the settled frame. */
    fun settleNow() {
        if (settleRequested.compareAndSet(false, true)) requestRender()
    }

    /** Replays the morph: photo, morph, dwell on the settled effect, rewind. For previews. */
    fun startMorphLoop() {
        loopMorph.set(true)
        loopRestart.set(true)
        requestRender()
    }

    fun stopMorphLoop() {
        loopMorph.set(false)
        loopRestart.set(false)
    }

    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        forgetContextObjects()

        quad.create()
        fbo = AtmosphereGl.createFramebuffer()

        val quadVert = AtmosphereGl.readAsset(context, "$SHADER_DIR/quad.vert")
        compositeProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/composite.frag"), "composite"
        )
        blurProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/blur.frag"), "blur"
        )
        grainProgram = AtmosphereGl.buildProgram(
            quadVert, AtmosphereGl.readAsset(context, "$SHADER_DIR/grain.frag"), "grain"
        )
        shapes.onSurfaceCreated(
            AtmosphereGl.readAsset(context, "$SHADER_DIR/blob.vert"),
            AtmosphereGl.readAsset(context, "$SHADER_DIR/blob.frag")
        )

        uBgColor = GLES30.glGetUniformLocation(compositeProgram, "uBgColor")
        uMixBlend = GLES30.glGetUniformLocation(compositeProgram, "uMixBlend")
        uCoverScale = GLES30.glGetUniformLocation(compositeProgram, "uCoverScale")
        uGlass = GLES30.glGetUniformLocation(compositeProgram, "uGlass")
        uGlassSize = GLES30.glGetUniformLocation(compositeProgram, "size")
        uXFactorA = GLES30.glGetUniformLocation(compositeProgram, "xFactorA")
        uXFactorB = GLES30.glGetUniformLocation(compositeProgram, "xFactorB")
        uXFactorC = GLES30.glGetUniformLocation(compositeProgram, "xFactorC")

        uKernel = GLES30.glGetUniformLocation(blurProgram, "uKernel")
        uBlurRadius = GLES30.glGetUniformLocation(blurProgram, "uBlurRadius")
        uBlurOffset = GLES30.glGetUniformLocation(blurProgram, "uBlurOffset")

        uNoiseGrowth = GLES30.glGetUniformLocation(grainProgram, "uNoiseGrowth")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        AtmosphereGl.bindSamplerToUnit0(compositeProgram, "s_TextureMap")
        AtmosphereGl.bindSamplerToUnit0(blurProgram, "screenTexture")
        AtmosphereGl.bindSamplerToUnit0(grainProgram, "uSampler")

        // A new context has no photo; unless one is already queued, ask for it.
        if (pendingSource.get() == null) onSourceMissing()
    }

    private fun forgetContextObjects() {
        photoTexture = 0
        photoWidth = 0
        photoHeight = 0
        fbo = 0
        prerollTargets.forget()
        effectTargets.forget()
        uploadedKernelRadius = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val dimensionsChanged = width != surfaceWidth || height != surfaceHeight
        surfaceWidth = width
        surfaceHeight = height
        easedRenderRate = AtmosphereConstants.easedRenderRate(width)
        updateCoverScale()
        prerollTargets.ensure(
            (width / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1),
            (height / AtmosphereConstants.PREROLL_DOWNSCALE).coerceAtLeast(1)
        )
        effectTargets.ensure(
            AtmosphereConstants.EFFECT_RASTER_WIDTH,
            (AtmosphereConstants.EFFECT_RASTER_WIDTH * height / width.coerceAtLeast(1)).coerceAtLeast(1)
        )
        if (dimensionsChanged) shapes.reset(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        applyPendingWork()

        // Self-clocked to 16 ms a frame so the morph lasts ~2.96 s on any refresh rate.
        val elapsed = SystemClock.elapsedRealtime() - lastFrameAt
        if (elapsed < AtmosphereConstants.FRAME_INTERVAL_MS) {
            SystemClock.sleep(AtmosphereConstants.FRAME_INTERVAL_MS - elapsed)
        }
        lastFrameAt = SystemClock.elapsedRealtime()

        if (!animating) frame = AtmosphereConstants.SETTLED_FRAME
        drawFrame(frame, isInit = !animating)

        if (animating) {
            if (loopPhotoHold > 0) {
                loopPhotoHold--
                requestRender()
            } else if (frame <= AtmosphereConstants.TOTAL_ANIM_FRAMES) {
                frame++
                requestRender()
            } else if (loopMorph.get()) {
                if (loopDwell < AtmosphereConstants.LOOP_DWELL_FRAMES) {
                    loopDwell++
                    requestRender()
                } else {
                    restartMorph()
                }
            } else {
                animating = false
            }
        }
    }

    // Frame

    private fun drawFrame(frameNumber: Int, isInit: Boolean) {
        if (photoTexture == 0 || seeds.size != VertexInfo.SEED_COUNT) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        if (locked.get() || loopPhotoHold > 0 || !effectEnabled) {
            drawLockScreen()
            return
        }

        val targets = if (frameNumber < AtmosphereConstants.FIRST_BLOB_FRAME) prerollTargets else effectTargets
        val w = targets.width
        val h = targets.height

        // Pass 1+2: the photo pulled toward the background colour, blobs over it.
        AtmosphereGl.bindFramebuffer(fbo, targets.texA, w, h)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        drawComposite(mixBlendAt(frameNumber))
        shapes.draw(frameNumber, isInit, surfaceWidth, surfaceHeight)

        // Pass 3+4: one separable Gaussian over photo and blobs together.
        val blurRate = AtmosphereConstants.easedRamp(frameNumber, to = AtmosphereConstants.BG_BLUR_GROWTH)
        val radius = (easedRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_RADIUS).toInt()
        val offset = easedRenderRate * blurRate * AtmosphereConstants.MAX_BLUR_OFFSET

        // Nothing never disables blending (the library doesn't import glDisable): both blur passes
        // and the grain are drawn SRC_ALPHA / ONE_MINUS_SRC_ALPHA into uncleared targets. While the
        // blobs fade in, the composite's alpha drops below 1, so a little of the previous frame stays.
        GLES30.glUseProgram(blurProgram)
        uploadKernel(radius)

        // Offset is in panel pixels, so normalise by the surface, not the render target.
        AtmosphereGl.bindFramebuffer(fbo, targets.texB, w, h)
        drawBlurPass(targets.texA, radius, offset / surfaceWidth, 0f)
        AtmosphereGl.bindFramebuffer(fbo, targets.texC, w, h)
        drawBlurPass(targets.texB, radius, 0f, offset / surfaceHeight)

        // Pass 5: grain, and the bilinear magnify that turns flat polygons into gradients.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glUseProgram(grainProgram)
        AtmosphereGl.bindTexture0(targets.texC)
        GLES30.glUniform1f(uNoiseGrowth, noiseGrowthAt(frameNumber))
        quad.draw()
    }

    private fun drawLockScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        drawComposite(0f)
    }

    private fun drawComposite(mixBlend: Float) {
        GLES30.glUseProgram(compositeProgram)
        AtmosphereGl.bindTexture0(photoTexture)
        GLES30.glUniform3f(uBgColor, bgColor[0], bgColor[1], bgColor[2])
        GLES30.glUniform1f(uMixBlend, mixBlend)
        GLES30.glUniform2f(uCoverScale, coverScale[0], coverScale[1])
        GLES30.glUniform1f(uGlass, if (glassEnabled) 1f else 0f)
        GLES30.glUniform1f(uGlassSize, glassSize().toFloat())
        GLES30.glUniform1f(uXFactorA, AtmosphereConstants.GLASS_X_FACTOR_A)
        GLES30.glUniform1f(uXFactorB, AtmosphereConstants.GLASS_X_FACTOR_B)
        GLES30.glUniform1f(uXFactorC, AtmosphereConstants.GLASS_X_FACTOR_C)
        quad.draw()
    }

    private fun drawBlurPass(source: Int, radius: Int, offsetX: Float, offsetY: Float) {
        AtmosphereGl.bindTexture0(source)
        GLES30.glUniform1i(uBlurRadius, radius)
        GLES30.glUniform2f(uBlurOffset, offsetX, offsetY)
        quad.draw()
    }

    /** Gaussian weights, sigma = radius / 3, normalised two-sided. Re-sent only when radius moves. */
    private fun uploadKernel(radius: Int) {
        if (radius == uploadedKernelRadius) return
        uploadedKernelRadius = radius

        kernel.fill(0f)
        if (radius > 0) {
            val sigma = radius / 3f
            val norm = 1f / sqrt(2f * Math.PI.toFloat() * sigma * sigma)
            val twoSigmaSquared = 2f * sigma * sigma
            kernel[0] = norm
            var sum = kernel[0]
            val n = radius.coerceAtMost(AtmosphereConstants.BLUR_RADIUS_LIMIT)
            for (i in 1 until n) {
                kernel[i] = norm * exp(-(i * i) / twoSigmaSquared)
                sum += 2f * kernel[i]
            }
            if (sum > 0f) for (i in 0 until n) kernel[i] /= sum
        }
        GLES30.glUniform1fv(uKernel, AtmosphereConstants.BLUR_RADIUS_LIMIT, kernel, 0)
    }

    private fun mixBlendAt(frameNumber: Int): Float = AtmosphereConstants.easedRamp(
        frameNumber,
        from = AtmosphereConstants.FIRST_BLOB_FRAME,
        to = AtmosphereConstants.BG_TEXTURE_TRANSITION
    )

    /**
     * Nothing's `round(32 * imageDisplayedWidth / viewWidth)`: the rib count across the whole
     * image, so that the user's setting (default 32) is the count across one screen width.
     */
    private fun glassSize(): Int =
        Math.round(ribCount.coerceAtLeast(1) / coverScale[0].coerceAtLeast(1e-3f)).coerceAtLeast(1)

    /** The grain is the one linear ramp: 0 -> 14% over the first 80 frames, render rate 1.0. */
    private fun noiseGrowthAt(frameNumber: Int): Float =
        AtmosphereConstants.MAX_NOISE *
            AtmosphereConstants.ramp(frameNumber, to = AtmosphereConstants.NOISE_GROWTH)

    private fun updateCoverScale() {
        coverScale[0] = 1f
        coverScale[1] = 1f
        if (photoWidth <= 0 || photoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val photoAspect = photoWidth.toFloat() / photoHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        // A screen-shaped photo is stretched to the panel, as Nothing does with its pre-cropped
        // image; only a photo of a different shape is cover-cropped.
        if (kotlin.math.abs(photoAspect / surfaceAspect - 1f) < SCREEN_ASPECT_TOLERANCE) return
        if (surfaceAspect > photoAspect) coverScale[1] = photoAspect / surfaceAspect
        else coverScale[0] = surfaceAspect / photoAspect
    }

    // State changes

    private fun applyPendingWork() {
        if (settleRequested.getAndSet(false)) {
            loopPhotoHold = 0
            animating = false
        }

        if (lockStateDirty.getAndSet(false)) {
            loopPhotoHold = 0
            if (locked.get()) {
                frame = 0
                animating = false
                // Every lock re-rolls the layout, so no two unlocks settle the same way.
                shapes.reset(surfaceWidth, surfaceHeight)
            } else if (animateUnlock.get()) {
                frame = 0
                animating = true
                lastFrameAt = SystemClock.elapsedRealtime()
                // A lock/unlock pair can coalesce before this thread sees it (screen on with no
                // keyguard), so roll the layout here too rather than replaying a spent one.
                shapes.reset(surfaceWidth, surfaceHeight)
            } else {
                animating = false
            }
        }

        if (loopRestart.getAndSet(false)) restartMorph()

        val pending = pendingSource.getAndSet(null) ?: return
        adoptSource(pending.first, pending.second)
        if (loopMorph.get()) restartMorph() else {
            // Nothing's M() -> A(): a new photo restarts the frame counter; unlocked, the morph replays.
            frame = 0
            animating = !locked.get() && effectEnabled
            lastFrameAt = SystemClock.elapsedRealtime()
            if (animating) requestRender()
        }
    }

    private fun restartMorph() {
        frame = 0
        loopDwell = 0
        loopPhotoHold = AtmosphereConstants.LOOP_PHOTO_HOLD_FRAMES
        lastFrameAt = SystemClock.elapsedRealtime()
        shapes.reset(surfaceWidth, surfaceHeight)
        animating = true
        requestRender()
    }

    private fun adoptSource(newSeeds: List<VertexInfo>, bitmap: Bitmap) {
        try {
            val newTexture = AtmosphereGl.uploadPhoto(bitmap)
            AtmosphereGl.deleteTexture(photoTexture)
            photoTexture = newTexture
            photoWidth = bitmap.width
            photoHeight = bitmap.height
            seeds = newSeeds
            updateCoverScale()
            // Entry 0 is the background the whole composite is pulled toward, not a blob.
            AtmosphereGl.unpackRgb(newSeeds[0].pixelColor, bgColor)
            shapes.setSeeds(newSeeds)
            shapes.reset(surfaceWidth, surfaceHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adopt new source; keeping the previous one", e)
        } finally {
            bitmap.recycle()
        }
    }

    fun release() {
        shapes.release()
        prerollTargets.release()
        effectTargets.release()
        AtmosphereGl.deleteTexture(photoTexture)
        photoTexture = 0
        AtmosphereGl.deleteFramebuffer(fbo)
        fbo = 0
        quad.release()
        pendingSource.getAndSet(null)?.second?.recycle()
    }
}
