package org.forestnav.app.map

/**
 * Decides when unsaved edits are written out.
 *
 * The clock is passed in rather than read from the system so the timing can be tested
 * directly. An autosave that cannot be tested is one that either fires constantly in the
 * field or never fires at all, and both are found out on a hillside rather than in a
 * build.
 *
 * An interval of `null` disables autosave entirely, which is what a blank A8 means.
 */
class AutoSaver(
    /** Seconds between saves, or null to never save automatically. */
    intervalSec: Int?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Normalised interval: 0 when disabled, so the comparison below is simple. */
    private val intervalMs: Long = (intervalSec ?: 0).coerceAtLeast(0) * 1000L

    /** Set once something is actually waiting to be written. */
    private var pendingSince: Long? = null

    val enabled: Boolean get() = intervalMs > 0

    /**
     * Record that there are unsaved edits, starting the clock if this is the first.
     *
     * Returns true when [due] should be called; kept separate so a caller can mark dirty
     * on every event without losing the original starting time.
     */
    fun markDirty(): Boolean {
        if (!enabled) return false
        if (pendingSince == null) pendingSince = now()
        return due()
    }

    /** True when the interval has elapsed and unsaved edits are waiting. */
    fun due(): Boolean {
        if (!enabled) return false
        val since = pendingSince ?: return false
        return now() - since >= intervalMs
    }

    /** Record that everything has been written. */
    fun markClean() {
        pendingSince = null
    }

    /** Seconds still to wait, 0 when due or disabled. */
    fun remainingSec(): Int {
        val since = pendingSince ?: return 0
        val left = intervalMs - (now() - since)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** Seconds between saves, or null when disabled. */
    val everySec: Int? get() = if (enabled) (intervalMs / 1000L).toInt() else null
}