package org.forestnav.app.map

/**
 * Rejects location fixes that arrive out of order.
 *
 * Android can deliver a cached fix after a newer one, and when it does, whatever stores
 * "the current position" silently jumps backwards. That was observed on the emulator: the
 * map showed one position while the flight plan was choosing its next call from a fix
 * 1300 km away, because a stale fix had taken over.
 *
 * The guard is on **elapsed realtime**, not wall-clock time, so changing the device clock
 * cannot make every subsequent fix look older than the last one.
 *
 * A fix exactly as old as the newest is refused too: two fixes stamped identically are a
 * replay, not new information.
 */
class FixGuard {
    private var newestElapsedMs = Long.MIN_VALUE

    /** Fixes refused as stale since the last [reset]. */
    var staleRejected: Int = 0
        private set

    /**
     * Accept [elapsedMs] only if it is newer than everything seen so far.
     *
     * Returns true when the fix should be used.
     */
    fun accept(elapsedMs: Long): Boolean {
        if (elapsedMs <= newestElapsedMs) {
            staleRejected++
            return false
        }
        newestElapsedMs = elapsedMs
        return true
    }

    /** True once a fix has been accepted. */
    val hasFix: Boolean get() = newestElapsedMs != Long.MIN_VALUE

    /** Forget everything, for when location is switched off and on again. */
    fun reset() {
        newestElapsedMs = Long.MIN_VALUE
        staleRejected = 0
    }
}