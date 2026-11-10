package io.github.shashtwat.atmosphere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import io.github.shashtwat.atmosphere.engine.SeedExtractor
import io.github.shashtwat.atmosphere.engine.VertexInfo
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** The photo plus its six palette seeds: everything the renderer needs. */
class AtmosphereSource(val bitmap: Bitmap, val seeds: List<VertexInfo>)

/** Stores the picked photo privately and its extracted seeds next to it. Blocking calls. */
object ImageStore {
    private const val MAX_SIDE = 2560

    // Nothing saves its final image as a screen-cropped PNG and extracts the seeds from that file.
    private fun imageFile(context: Context, draft: Boolean = false) =
        File(context.filesDir, if (draft) "draft.png" else "wallpaper.png")
    /** Before the PNG change: an uncropped JPEG, converted on first load. */
    private fun legacyFile(context: Context) = File(context.filesDir, "wallpaper.jpg")
    private fun seedFile(context: Context, draft: Boolean = false) =
        File(context.filesDir, if (draft) "draft-seeds.txt" else "seeds-nos5.txt") // renamed: seeds from the old extractor are stale

    /** A photo picked in the app but not applied yet (shown only in the app's preview). */
    fun hasDraft(context: Context) = imageFile(context, true).exists()

    fun clearDraft(context: Context) { imageFile(context, true).delete(); seedFile(context, true).delete() }

    /** Makes the draft the wallpaper's photo. */
    fun commitDraft(context: Context): Boolean {
        if (!hasDraft(context)) return false
        seedFile(context, true).renameTo(seedFile(context))
        return imageFile(context, true).renameTo(imageFile(context))
    }

    /** Decodes the picked photo, crops it to the screen, saves it (as the draft), then extracts its seeds. */
    fun save(context: Context, uri: Uri, draft: Boolean = false) {
        val decoded = if (android.os.Build.VERSION.SDK_INT >= 28) decodeModern(context, uri) else decodeLegacy(context, uri)
        writeImage(context, decoded, draft)
        decoded.recycle()
    }

    @androidx.annotation.RequiresApi(28)
    private fun decodeModern(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
    }

    /** Android 8: BitmapFactory, downsampled to about [MAX_SIDE], with the EXIF rotation applied. */
    private fun decodeLegacy(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("undecodable image")
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (degrees == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /**
     * Centre-crops [photo] to the screen's shape at the screen's resolution, as Nothing's picker
     * does before saving, writes it as PNG and extracts the seeds from that file.
     */
    private fun writeImage(context: Context, photo: Bitmap, draft: Boolean) {
        val (w, h) = screenSize(context)
        val sw = minOf(w, h); val sh = maxOf(w, h)
        val scale = max(sw.toFloat() / photo.width, sh.toFloat() / photo.height)
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(photo, android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((sw - photo.width * scale) / 2f, (sh - photo.height * scale) / 2f)
        }, Paint(Paint.FILTER_BITMAP_FLAG))
        // Per process: the app and the wallpaper (":atmosphere") may both write on first run.
        val tmp = File(context.filesDir, "wallpaper-${android.os.Process.myPid()}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Seeds come from the stored file, decoded at inSampleSize 16, as Nothing does.
        writeSeeds(context, SeedExtractor.extract(tmp.path) ?: SeedExtractor.extract(bitmap), draft)
        tmp.renameTo(imageFile(context, draft))
        bitmap.recycle()
    }

    /** The current photo and seeds (the draft first when [draft] and there is one), or a placeholder. */
    fun load(context: Context, draft: Boolean = false): AtmosphereSource {
        migrateLegacy(context)
        if (draft && hasDraft(context)) runCatching {
            val bmp = BitmapFactory.decodeFile(imageFile(context, true).path)
            val seeds = readSeeds(context, true) ?: SeedExtractor.extract(bmp)
            return AtmosphereSource(bmp, seeds)
        }
        if (!imageFile(context).exists()) adoptDefault(context)
        val bitmap = imageFile(context).takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?: return placeholder().let { AtmosphereSource(it, SeedExtractor.extract(it)) }
        val seeds = readSeeds(context)
            ?: (SeedExtractor.extract(imageFile(context).path) ?: SeedExtractor.extract(bitmap))
                .also { writeSeeds(context, it) }
        return AtmosphereSource(bitmap, seeds)
    }

    /**
     * First run, nothing picked yet: the newest image in the user's wallpaper folder (the folder
     * they chose, or Pictures/Wallpapers) becomes the wallpaper; without access to it, the bundled
     * default. Blocking; runs on the loader's thread.
     */
    private fun adoptDefault(context: Context) {
        val fromFolder = runCatching {
            val tree = WallpaperFolder.tree(context)
            when {
                tree != null -> WallpaperFolder.treeImages(context, tree).firstOrNull()
                WallpaperFolder.granted(context) -> WallpaperFolder.images(context).firstOrNull()
                else -> null
            }
        }.getOrNull()
        if (fromFolder != null && runCatching { save(context, fromFolder) }.isSuccess) {
            AtmosphereSettings.prefs(context).edit().putString("picked_uri", fromFolder.toString()).apply()
            return
        }
        runCatching {
            val bmp = context.assets.open("default_wallpaper.png").use { BitmapFactory.decodeStream(it) }
            writeImage(context, bmp, draft = false)
            bmp.recycle()
        }
    }

    /** The full panel size in pixels (portrait or landscape, whatever the display reports). */
    @Suppress("DEPRECATION")
    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { return it.width() to it.height() }
        val m = android.util.DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** The pre-PNG wallpaper (uncropped JPEG): crop, save as PNG and re-extract, once. */
    private fun migrateLegacy(context: Context) {
        val old = legacyFile(context)
        if (!old.exists() || imageFile(context).exists()) return
        runCatching {
            val bmp = BitmapFactory.decodeFile(old.path) ?: return
            writeImage(context, bmp, draft = false)
            bmp.recycle()
        }
        old.delete()
    }

    /** A small copy of the wallpaper photo, for the system's wallpaper colours. */
    fun thumbnail(context: Context): Bitmap? = imageFile(context).takeIf { it.exists() }?.let {
        BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 8 })
    }

    private fun writeSeeds(context: Context, seeds: List<VertexInfo>, draft: Boolean = false) {
        seedFile(context, draft).writeText(seeds.joinToString("\n") {
            "${it.x},${it.y},${it.bitmapWidth},${it.bitmapHeight},${it.pixelColor},${it.population}"
        })
    }

    private fun readSeeds(context: Context, draft: Boolean = false): List<VertexInfo>? = runCatching {
        seedFile(context, draft).readLines().filter { it.isNotBlank() }.map { line ->
            val v = line.split(',').map { it.trim().toInt() }
            VertexInfo(v[0], v[1], v[2], v[3], v[4], v[5])
        }.takeIf { it.size == VertexInfo.SEED_COUNT }
    }.getOrNull()

    private fun placeholder(): Bitmap {
        val w = 1080; val h = 2340
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF101626.toInt(), 0xFF2A1B4D.toInt(), 0xFF0E2A3A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        fun blob(x: Float, y: Float, r: Float, color: Int) {
            p.shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        blob(w * 0.25f, h * 0.3f, w * 0.7f, 0xFFFF6E40.toInt())
        blob(w * 0.8f, h * 0.55f, w * 0.6f, 0xFF7C4DFF.toInt())
        blob(w * 0.4f, h * 0.85f, w * 0.65f, 0xFF00BFA5.toInt())
        blob(w * 0.7f, h * 0.12f, w * 0.4f, 0xFFFFD54F.toInt())
        return bmp
    }
}
