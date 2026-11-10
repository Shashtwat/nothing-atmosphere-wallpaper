package io.github.shashtwat.atmosphere.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.util.Log
import io.github.shashtwat.atmosphere.ImageStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dev QA (adb, needs DUMP): renders the unlock morph off-screen with the current photo, at the
 * real panel size, and writes chosen frames to files/morph/fNNN.png.
 * `adb shell am broadcast -n io.github.shashtwat.atmosphere/.engine.MorphDump [--es frames 0,10,20] [--es image <file>]`
 */
class MorphDump : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try { run(app, intent.getStringExtra("frames"), intent.getStringExtra("image"), intent.getIntExtra("width", 0)) } catch (e: Throwable) { Log.e("MorphDump", "failed", e) }
            finally { pending.finish() }
        }.start()
    }

    /** [imagePath]: optional image file (readable by the app) to render instead of the current wallpaper. */
    private fun run(ctx: Context, framesArg: String?, imagePath: String? = null, outWidth: Int = 0) {
        val dm = ctx.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels); val h = maxOf(dm.widthPixels, dm.heightPixels)
        val want = framesArg?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: setOf(0, 8, 10, 12, 14, 16, 20, 25, 30, 40, 50, 65, 80, 100, 135, 186)
        val dir = File(ctx.getExternalFilesDir(null), "morph").apply { deleteRecursively(); mkdirs() }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        ), 0, cfgs, 0, 1, IntArray(1), 0)
        val egl = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surf, surf, egl)
        try {
            val r = AtmosphereRenderer(ctx, {})
            r.onSurfaceCreated(null, null)
            r.onSurfaceChanged(null, w, h)
            if (imagePath != null) {
                val bmp = android.graphics.BitmapFactory.decodeFile(imagePath)
                r.queueSource(SeedExtractor.extract(imagePath) ?: SeedExtractor.extract(bmp), bmp)
            } else {
                val src = ImageStore.load(ctx)
                r.queueSource(src.seeds, src.bitmap)
            }
            r.onDrawFrame(null)            // adopts the photo, draws the lock screen
            r.setLocked(false)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (f in 0..186) {
                r.onDrawFrame(null)
                if (f !in want) continue
                buf.rewind()
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { buf.rewind(); copyPixelsFromBuffer(buf) }
                val flipped = Bitmap.createBitmap(bmp, 0, 0, w, h, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
                val out = if (outWidth in 1 until w) Bitmap.createScaledBitmap(flipped, outWidth, outWidth * h / w, true) else flipped
                File(dir, "f%03d.png".format(f)).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
                if (out !== flipped) out.recycle()
                bmp.recycle(); flipped.recycle()
            }
            r.release()
            Log.i("MorphDump", "done ${dir.absolutePath}")
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surf); EGL14.eglDestroyContext(display, egl)
        }
    }
}
