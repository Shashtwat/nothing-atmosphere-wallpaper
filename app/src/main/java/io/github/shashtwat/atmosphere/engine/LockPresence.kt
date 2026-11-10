/*
 * Ported from Nothing Wallpaper Changer by NineCSdev (MIT License)
 * https://github.com/NineCSdev/nothing-wallpaper-changer
 */
package io.github.shashtwat.atmosphere.engine

/**
 * Nothing's delay between screen-off and returning to the lock visual, before the animator
 * duration scale: `500 * ValueAnimator.getDurationScale()` (0 when animations are off).
 */
const val BASE_LOCK_DELAY_MS = 500L

/**
 * A screen-on this soon after a screen-off is ignored (Nothing: the camera double-press gesture
 * toggles the panel), so the visual neither resets nor replays.
 */
private const val QUICK_TOGGLE_MS = 450L

/** How long after the keyguard starts to disappear a newly visible engine still counts as *that* unlock's reveal */
private const val UNLOCK_REVEAL_WINDOW_MS = 500L

/**
 * Something the engine observed. Two kinds, and the difference is the whole point:
 *
 * - **Edges** ([Unlocked]) are transitions. They can be *held* until somebody is watching.
 * - **Levels** (`keyguardLocked` payloads) are readings of how things stand right now. Applied immediately.
 *
 * Readings never travel alone so there is no way to feed a reading and have it mistaken for a transition.
 */
sealed interface LockEvent {
    /** The engine was created. [keyguardLocked] seeds the visual; false for a preview engine. */
    data class Created(val keyguardLocked: Boolean) : LockEvent

    /**
     * The panel came on. The only event a screen toggle faster than the ~190 ms visibility takes to
     * drop cannot skip, which is why the lock state is reconciled here rather than only on edges.
     */
    data class ScreenOn(val keyguardLocked: Boolean) : LockEvent

    /** The panel went off. */
    data object ScreenOff : LockEvent

    /** The keyguard began to disappear. */
    data object Unlocked : LockEvent

    /** The engine is being shown. [keyguardLocked] is read at this instant. */
    data class BecameVisible(val keyguardLocked: Boolean) : LockEvent

    /** The engine is being hidden. */
    data object BecameInvisible : LockEvent

    /** The lock-delay timer fired. */
    data object LockResetDue : LockEvent

    /** The engine is being torn down. */
    data object Destroyed : LockEvent
}

/** Something the engine should do about it. Executed in list order, immediately, on the main looper. */
sealed interface LockDecision {
    /**
     * @param locked true to show the plain photo, false for the effect.
     * @param animate whether a false edge plays the morph.
     */
    data class SetLocked(val locked: Boolean, val animate: Boolean) : LockDecision

    /**
     * Land on the settled frame with no transition. Distinct from `SetLocked(_, animate = false)`:
     * they differ in what happens to a morph already in flight.
     */
    data object SettleNow : LockDecision

    /** @param atMs absolute, on the same clock as the `nowMs` passed to [LockPresence.on]. */
    data class ScheduleLockReset(val atMs: Long) : LockDecision

    data object CancelLockReset : LockDecision
}

/**
 * Decides whether an unlock morphs or settles silently.
 *
 * **Three things the caller must get right, none of which the types enforce:**
 *
 * 1. **[visible] here is authoritative.** Never pass the engine's own visibility alongside an event.
 *    The dismissal is signalled a beat *before* the engine is made visible, and that gap is exactly
 *    where two copies of this flag would disagree.
 * 2. **A preview engine feeds [LockEvent.Created] with `false` and nothing else.** This class has no
 *    notion of preview; a preview's entire lock behavior is that seed.
 * 3. **[LockEvent.BecameInvisible]'s decisions must run before the superclass's visibility
 *    handling**  so [LockDecision.SettleNow] has to land first. [LockEvent.BecameVisible]'s run after.
 */
class LockPresence(
    /** Read at each screen-off; the host scales [BASE_LOCK_DELAY_MS] by the animator scale. */
    private val lockDelayMs: () -> Long = { BASE_LOCK_DELAY_MS },
    /** Nothing applies the quick-toggle rule only while the camera double-press gesture is on. */
    private val quickToggleEnabled: () -> Boolean = { true },
) {

    /** Seeded to match the renderer, which starts on the lock visual. */
    private var locked = true

    /** Starts false, matching an engine that has not been shown yet. */
    private var visible = false

    /** When the keyguard last began to disappear with nobody watching, or 0 for none held. Always consumed. */
    private var heldUnlockAtMs = 0L

    private var destroyed = false

    /** When the panel last went off, or 0 for not since creation. */
    private var screenOffAtMs = 0L

    fun on(event: LockEvent, nowMs: Long): List<LockDecision> {
        if (destroyed) return emptyList()

        return when (event) {
            is LockEvent.Created -> {
                locked = event.keyguardLocked
                // Always emitted, unlike every other SetLocked: the seed has to reach a renderer that may already agree.
                // Created unlocked, Nothing plays the morph (its StateFlow's first value runs B(true)).
                listOf(LockDecision.SetLocked(locked, animate = !locked))
            }

            is LockEvent.ScreenOn -> {
                val offAt = screenOffAtMs
                screenOffAtMs = 0L
                when {
                    // The keyguard is up: always the lock visual, whatever came before. Checked first so a
                    // quick off/on (the screen-off broadcast can land only ~130 ms before screen-on) can't
                    // leave the home-screen effect showing on the lock screen.
                    event.keyguardLocked -> {
                        heldUnlockAtMs = 0L
                        listOf(LockDecision.CancelLockReset) + setLocked(true, animate = false)
                    }

                    // Off and straight back on with no keyguard (camera double-press): leave the visual alone.
                    offAt != 0L && nowMs - offAt < QUICK_TOGGLE_MS && quickToggleEnabled() -> listOf(LockDecision.CancelLockReset)

                    // A live hold outranks the reading. Without this the keyguard, if it already
                    // reads clear by the time the panel comes on, would spend the hold's edge
                    // without a morph.
                    heldUnlockAtMs != 0L -> listOf(LockDecision.CancelLockReset)

                    // No keyguard to dismiss: as Nothing does, show the photo, then play the morph.
                    else -> {
                        locked = true
                        listOf(
                            LockDecision.CancelLockReset,
                            LockDecision.SetLocked(true, animate = false),
                        ) + applyOrHoldUnlock(nowMs)
                    }
                }
            }

            LockEvent.ScreenOff -> {
                // A held unlock cannot belong to a panel that has gone dark since.
                heldUnlockAtMs = 0L
                screenOffAtMs = nowMs
                listOf(
                    LockDecision.CancelLockReset,
                    LockDecision.ScheduleLockReset(nowMs + lockDelayMs().coerceAtLeast(0L))
                )
            }

            LockEvent.Unlocked -> applyOrHoldUnlock(nowMs)

            is LockEvent.BecameVisible -> {
                visible = true
                val held = heldUnlockAtMs
                heldUnlockAtMs = 0L

                when {
                    // The reveal this engine is being made visible *for*. It outranks the reading
                    // because the keyguard reports itself locked throughout its own dismissal
                    held != 0L && nowMs - held <= UNLOCK_REVEAL_WINDOW_MS -> setLocked(false, animate = true)

                    // A wake onto the lock screen shows the photo it will be dismissed from
                    event.keyguardLocked -> setLocked(true, animate = false)

                    // Held too long ago to be this reveal: right state, no morph
                    held != 0L -> setLocked(false, animate = false)

                    // A wake with no keyguard, or a return to a home screen that never locked
                    else -> applyOrHoldUnlock(nowMs)
                }
            }

            LockEvent.BecameInvisible -> {
                // Nothing keeps rendering while hidden: a morph in flight carries on, no settle.
                visible = false
                emptyList()
            }

            LockEvent.LockResetDue ->
                // animate is only consulted on a false edge, so passing false here says what this
                // is rather than relying on it being ignored.
                setLocked(true, animate = false)

            LockEvent.Destroyed -> {
                destroyed = true
                listOf(LockDecision.CancelLockReset)
            }
        }
    }

    /** An unlock reaches the screen if someone is watching, and waits its turn if nobody is. */
    private fun applyOrHoldUnlock(nowMs: Long): List<LockDecision> =
        if (visible) {
            setLocked(false, animate = true)
        } else {
            heldUnlockAtMs = nowMs
            emptyList()
        }

    /**
     * Change-gated, and load-bearing rather than an optimization: a decision list that repeated
     * the current state would stop describing what actually happens, and the tests would be
     * asserting on call cadence instead of behavior.
     */
    private fun setLocked(locked: Boolean, animate: Boolean): List<LockDecision> {
        if (this.locked == locked) return emptyList()
        this.locked = locked
        return listOf(LockDecision.SetLocked(locked, animate))
    }
}
