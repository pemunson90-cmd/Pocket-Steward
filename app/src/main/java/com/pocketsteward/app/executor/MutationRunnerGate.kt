package com.pocketsteward.app.executor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-wide runner owns the journal while executing, undoing or recovering writes. */
class MutationRunnerGate {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
