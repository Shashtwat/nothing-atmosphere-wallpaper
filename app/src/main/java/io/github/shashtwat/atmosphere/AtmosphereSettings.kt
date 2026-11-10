package io.github.shashtwat.atmosphere

import android.content.Context
import android.content.SharedPreferences

/**
 * Which photo the wallpaper shows. Stored in SharedPreferences; the wallpaper runs in its own
 * process (":atmosphere"), so saving also broadcasts [ACTION_CHANGED] for it to re-read.
 */
data class AtmosphereSettings(val imageVersion: Long = 0L) {
    fun save(prefs: SharedPreferences, context: Context? = null) {
        prefs.edit().putLong(K_IMAGE, imageVersion).commit()
        context?.sendBroadcast(android.content.Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    companion object {
        const val K_IMAGE = "image_version"
        const val ACTION_CHANGED = "io.github.shashtwat.atmosphere.ATMOSPHERE_CHANGED"

        /** MODE_MULTI_PROCESS: re-reads the file when the other process changed it. */
        @Suppress("DEPRECATION")
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("atmosphere", Context.MODE_MULTI_PROCESS)

        fun load(prefs: SharedPreferences) = AtmosphereSettings(prefs.getLong(K_IMAGE, 0L))
    }
}
