package com.pocketsteward.app.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One walker per scan root at a time. The background refresh and a scan
 * started by hand share a root's checkpoint; letting both walk it at once
 * would interleave their progress. Whoever arrives second waits, then
 * usually finds the work already done.
 */
class ScanLocks {
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(key: String): Mutex = synchronized(locks) {
        locks.getOrPut(key.trimEnd('/')) { Mutex() }
    }

    fun isBusy(key: String): Boolean = lockFor(key).isLocked

    suspend fun <T> withScanLock(key: String, block: suspend () -> T): T = lockFor(key).withLock { block() }
}
