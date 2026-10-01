package com.pocketsteward.app.library

/** Limits observation to inbox metadata; an event never authorizes a file operation. */
object InboxObservationPolicy {
    const val QUIET_PERIOD_MS = 3_000L
    const val MIN_REFRESH_INTERVAL_MS = 120_000L
    const val MAX_ROOTS = 32

    fun remainingDelay(now: Long, lastQueued: Long?): Long {
        if (lastQueued == null || now < lastQueued) return 0
        return (MIN_REFRESH_INTERVAL_MS - (now - lastQueued)).coerceAtLeast(0)
    }

    fun isWithinStorage(storageRoot: String, path: String): Boolean {
        val root = storageRoot.trimEnd('/')
        return root.isNotEmpty() && path.startsWith("$root/")
    }

    fun shouldRefresh(backgroundEnabled: Boolean, observed: Boolean, forced: Boolean): Boolean =
        if (observed) backgroundEnabled else forced || backgroundEnabled
}
