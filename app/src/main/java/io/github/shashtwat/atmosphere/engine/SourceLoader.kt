package io.github.shashtwat.atmosphere.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.shashtwat.atmosphere.AtmosphereSettings
import io.github.shashtwat.atmosphere.ImageStore
import java.util.concurrent.Executors

/**
 * Feeds one renderer: decodes the photo off the GL thread, and pushes settings changes.
 * Shared by the wallpaper engine and the in-app preview.
 */
/** [draft]: the in-app preview, which shows a picked-but-not-applied photo first. */
class SourceLoader(
    private val context: Context,
    private val draft: Boolean = false,
    /** Main thread, after a new photo was handed to the renderer. */
    private val onLoaded: () -> Unit = {},
    private val renderer: () -> AtmosphereRenderer,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = AtmosphereSettings.prefs(context)
    @Volatile private var closed = false
    private var lastImage = -1L

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        applySettings()
        if (key == AtmosphereSettings.K_IMAGE) reload()
    }

    /** The wallpaper runs in its own process; the app broadcasts after saving settings. */
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            applySettings()
            val v = AtmosphereSettings.load(prefs).imageVersion
            if (v != lastImage) { lastImage = v; reload() }
        }
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lastImage = AtmosphereSettings.load(prefs).imageVersion
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(AtmosphereSettings.ACTION_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applySettings()
    }

    /** Atmosphere on, Glass off: the only configuration this app ships. */
    fun applySettings() {
        renderer().setOptions(atmosphere = true, glass = false, ribs = AtmosphereConstants.GLASS_RIBS_PER_SCREEN)
    }

    /** Safe from any thread. */
    fun reload() {
        if (closed) return
        executor.execute {
            val source = runCatching { ImageStore.load(context, draft) }.getOrNull() ?: return@execute
            main.post {
                if (closed) source.bitmap.recycle()
                else { renderer().queueSource(source.seeds, source.bitmap); onLoaded() }
            }
        }
    }

    fun close() {
        closed = true
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        runCatching { context.unregisterReceiver(receiver) }
        executor.shutdownNow()
    }
}
