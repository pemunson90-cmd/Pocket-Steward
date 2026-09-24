package com.pocketsteward.app.library

/**
 * Decisions about the background library, kept free of Android so they are
 * unit tested.
 */
object LibraryPolicy {
    /** A folder scan may reuse the library instead of walking storage when the library finished this recently. */
    const val FRESH_FOR_SCANS_MS: Long = 60L * 60 * 1000

    /** A second refresh request arriving this soon after a finished walk is skipped. */
    const val SKIP_REPEAT_WALK_MS: Long = 10L * 60 * 1000

    /** Opening the app starts a quiet refresh when the library is older than this. */
    const val REFRESH_ON_OPEN_AFTER_MS: Long = 30L * 60 * 1000

    /** How often the scheduled refresh runs. WorkManager's floor is 15 minutes. */
    const val PERIODIC_REFRESH_HOURS: Long = 6

    fun isFresh(lastCompletedAt: Long?, now: Long, windowMs: Long): Boolean =
        lastCompletedAt != null && now >= lastCompletedAt && now - lastCompletedAt <= windowMs

    /**
     * Whether a scan of [target] can be answered from the library rooted at
     * [libraryRoot]. Plain paths: segment-aware containment, so
     * `/0/Download2` is not inside `/0/Download`. Document URIs from a granted
     * folder are opaque, so only the library root itself qualifies.
     */
    fun canAdopt(libraryRoot: String, target: String): Boolean {
        val root = libraryRoot.trimEnd('/')
        val t = target.trimEnd('/')
        if (root.startsWith("content://") || t.startsWith("content://")) return root == t
        return t == root || t.startsWith("$root/")
    }
}
