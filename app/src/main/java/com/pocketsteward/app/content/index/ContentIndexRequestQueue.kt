package com.pocketsteward.app.content.index

import com.pocketsteward.app.storage.StorageAccessMode

/** Requests received during an active foreground run remain available to that runner. */
class ContentIndexRequestQueue {
    data class Request(val root: String, val mode: StorageAccessMode)
    private val pending = linkedSetOf<Request>()
    private var runnerAttached = false
    /** Cancellation does not retire a runner until its cleanup has completed. Confined to Main. */
    fun attachRunner(): Boolean {
        if (runnerAttached) return false
        runnerAttached = true
        return true
    }
    fun retireRunner(): List<Request> {
        runnerAttached = false
        return drain()
    }
    fun add(roots: List<String>, mode: StorageAccessMode) {
        roots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.forEach { pending += Request(it, mode) }
    }
    fun take(): Request? = pending.firstOrNull()?.also { pending.remove(it) }
    fun drain(): List<Request> = pending.toList().also { pending.clear() }
}
