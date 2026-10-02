package com.pocketsteward.app.content.index

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** One lifecycle for the derived cache, independent of mutation authority. */
class ContentIndexCoordination {
    companion object { val Shared = ContentIndexCoordination() }
    private val revision = AtomicLong(0)
    private val commits = Mutex()
    private val documents = com.pocketsteward.app.library.ScanLocks()
    private val roots = com.pocketsteward.app.library.ScanLocks()
    fun epoch(): Long = revision.get()
    fun check(epoch: Long) {
        if (epoch != revision.get()) throw CancellationException("Content index was cleared. Start a new search to rebuild it.")
    }
    suspend fun <T> document(ref: String, epoch: Long, block: suspend () -> T): T = documents.withScanLock(ref) {
        check(epoch); block()
    }
    suspend fun <T> root(ref: String, epoch: Long, block: suspend () -> T): T = roots.withScanLock(ref) {
        check(epoch); block()
    }
    suspend fun pause(ref: String, epoch: Long, block: suspend () -> Unit) {
        check(epoch)
        roots.withScanLockIfAvailable(ref) { commit(epoch, block) }
    }
    suspend fun <T> commit(epoch: Long, block: suspend () -> T): T = commits.withLock {
        check(epoch); block()
    }
    suspend fun clear(block: suspend () -> Unit) {
        // Invalidate work before waiting for its current short transaction.
        revision.incrementAndGet()
        commits.withLock { block() }
    }
}
