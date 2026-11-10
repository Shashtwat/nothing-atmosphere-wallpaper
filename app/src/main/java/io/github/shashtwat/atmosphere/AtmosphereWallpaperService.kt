package io.github.shashtwat.atmosphere

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import io.github.shashtwat.atmosphere.engine.AtmosphereRenderer
import io.github.shashtwat.atmosphere.engine.BASE_LOCK_DELAY_MS
import io.github.shashtwat.atmosphere.engine.GLWallpaperService
import io.github.shashtwat.atmosphere.engine.LockDecision
import io.github.shashtwat.atmosphere.engine.LockEvent
import io.github.shashtwat.atmosphere.engine.LockPresence
import io.github.shashtwat.atmosphere.engine.SourceLoader

/**
 * The live wallpaper. Sharp photo on the lock screen; the Atmosphere morph plays as the keyguard
 * goes away, then the home screen holds the settled colour field.
 */
class AtmosphereWallpaperService : GLWallpaperService() {

    companion object {
        /** Sent by SystemUI to the wallpaper the moment the keyguard starts to disappear. */
        private const val COMMAND_KEYGUARD_GOING_AWAY = "android.wallpaper.keyguardgoingaway"
        private val LOCK_RESET_TOKEN = Any()
        /** One frame: the poll is our only unlock signal on Pixel, so it runs as tight as is useful. */
        private const val UNLOCK_POLL_MS = 16L
        /** Nothing raises the render thread to -8 (URGENT_DISPLAY) for 3.5 s after an unlock. */
        private const val MORPH_THREAD_PRIORITY = Process.THREAD_PRIORITY_URGENT_DISPLAY
        private const val MORPH_BOOST_MS = 3_500L
        private val PRIORITY_RESTORE_TOKEN = Any()
    }

    override fun onCreateEngine(): Engine = AtmosphereEngine()

    private inner class AtmosphereEngine : GLEngine() {
        private var boosted = false

        private val renderer: AtmosphereRenderer = AtmosphereRenderer(
            context = this@AtmosphereWallpaperService,
            requestRender = { requestRender() },
            onSourceMissing = { loader.reload() },
        )
        private val loader: SourceLoader =
            SourceLoader(this@AtmosphereWallpaperService, onLoaded = { if (Build.VERSION.SDK_INT >= 27) notifyColorsChanged() }) { renderer }

        /** Nothing reports its photo's colours (final_colors) so the system theme can follow them. */
        @androidx.annotation.RequiresApi(27)
        override fun onComputeColors(): android.app.WallpaperColors? =
            runCatching { ImageStore.thumbnail(this@AtmosphereWallpaperService)?.let { android.app.WallpaperColors.fromBitmap(it) } }.getOrNull()
        private val handler = Handler(Looper.getMainLooper())
        private val keyguardManager by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
        /** Nothing's `500 * ValueAnimator.getDurationScale()`, or 0 with animations off. */
        private val lockPresence = LockPresence(
            lockDelayMs = {
                // ValueAnimator.getDurationScale() is API 33; the global setting is the same value.
                val scale = if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
                    else android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                if (scale > 0f) (BASE_LOCK_DELAY_MS * scale).toLong() else 0L
            },
            quickToggleEnabled = {
                android.provider.Settings.Secure.getInt(contentResolver, "camera_double_tap_power_gesture_disabled", 0) == 0
            },
        )

        /**
         * Some builds (Pixel, Android 17) deliver neither COMMAND_KEYGUARD_GOING_AWAY nor
         * ACTION_USER_PRESENT to the wallpaper. So while the engine is visible behind the
         * keyguard, poll it and treat the falling edge as the unlock.
         */
        private val unlockPoll = object : Runnable {
            override fun run() {
                if (!isVisible || isPreview) return
                if (keyguardManager.isKeyguardLocked) {
                    handler.postDelayed(this, UNLOCK_POLL_MS)
                } else {
                    Log.i("Atmosphere", "poll: keyguard gone")
                    dispatch(LockEvent.Unlocked)
                }
            }
        }

        private fun startUnlockPoll() {
            handler.removeCallbacks(unlockPoll)
            if (!isPreview && keyguardManager.isKeyguardLocked) handler.post(unlockPoll)
        }

        private fun dispatch(event: LockEvent) {
            val decisions = lockPresence.on(event, SystemClock.uptimeMillis())
            Log.i("Atmosphere", "preview=$isPreview event=$event -> $decisions")
            for (decision in decisions) {
                when (decision) {
                    is LockDecision.SetLocked -> {
                        renderer.setLocked(decision.locked, decision.animate)
                        // Nothing boosts the GL thread once in the service's life (flag never reset).
                        if (!decision.locked && decision.animate && !boosted) {
                            boosted = true
                            boostRenderThread()
                        }
                    }
                    LockDecision.SettleNow -> {
                        renderer.stopMorphLoop()
                        renderer.settleNow()
                    }
                    is LockDecision.ScheduleLockReset ->
                        handler.postAtTime({ dispatch(LockEvent.LockResetDue) }, LOCK_RESET_TOKEN, decision.atMs)
                    LockDecision.CancelLockReset -> handler.removeCallbacksAndMessages(LOCK_RESET_TOKEN)
                }
            }
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> dispatch(LockEvent.ScreenOff)
                    Intent.ACTION_SCREEN_ON -> {
                        dispatch(LockEvent.ScreenOn(keyguardManager.isKeyguardLocked))
                        startUnlockPoll()
                    }
                    // Backstop for platforms that never send COMMAND_KEYGUARD_GOING_AWAY.
                    Intent.ACTION_USER_PRESENT -> dispatch(LockEvent.Unlocked)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setRenderer(renderer)
            loader.start()

            if (!isPreview) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    this@AtmosphereWallpaperService, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }

            dispatch(LockEvent.Created(!isPreview && keyguardManager.isKeyguardLocked))
            // The system apply screen's preview loops: photo, unlock morph, settled effect, again.
            if (isPreview) { renderer.setLocked(false, animate = false); renderer.startMorphLoop() }
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean,
        ): Bundle? {
            Log.i("Atmosphere", "onCommand $action")
            if (action == COMMAND_KEYGUARD_GOING_AWAY) dispatch(LockEvent.Unlocked)
            // Arrives ~150 ms before ACTION_SCREEN_ON: put the lock visual up before the panel lights.
            if (action == "android.wallpaper.wakingup" && keyguardManager.isKeyguardLocked) {
                dispatch(LockEvent.ScreenOn(true))
                startUnlockPoll()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (!visible) {
                handler.removeCallbacks(unlockPoll)
                dispatch(LockEvent.BecameInvisible)
            }
            super.onVisibilityChanged(visible)
            if (!visible) return
            if (isPreview) {
                renderer.startMorphLoop()
                return
            }
            dispatch(LockEvent.BecameVisible(keyguardManager.isKeyguardLocked))
            startUnlockPoll()
        }

        override fun onDestroy() {
            dispatch(LockEvent.Destroyed)
            if (!isPreview) runCatching { unregisterReceiver(receiver) }
            loader.close()
            handler.removeCallbacksAndMessages(null)
            queueGlEvent { renderer.release() }
            super.onDestroy()
        }

        /** Raises the GL thread's priority for the length of the morph, then restores it. */
        private fun boostRenderThread() {
            queueGlEvent { runCatching { Process.setThreadPriority(MORPH_THREAD_PRIORITY) } }
            handler.removeCallbacksAndMessages(PRIORITY_RESTORE_TOKEN)
            handler.postAtTime(
                { queueGlEvent { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) } } },
                PRIORITY_RESTORE_TOKEN,
                SystemClock.uptimeMillis() + MORPH_BOOST_MS,
            )
        }

    }
}
