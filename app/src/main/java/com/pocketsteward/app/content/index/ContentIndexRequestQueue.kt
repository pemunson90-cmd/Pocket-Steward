package com.pocketsteward.app.content.index

import com.pocketsteward.app.storage.StorageAccessMode

/** Requests received during an active foreground run remain available to that runner. */
class ContentIndexRequestQueue {
    data class Request(val root: String, val mode: StorageAccessMode)
    private val pending = linkedSetOf<Request>()
    fun add(roots: List<String>, mode: StorageAccessMode) {
        roots.map { it.trimEnd('/') }.filter { it.isNotBlank() }.forEach { pending += Request(it, mode) }
    }
    fun take(): Request? = pending.firstOrNull()?.also { pending.remove(it) }
    fun drain(): List<Request> = pending.toList().also { pending.clear() }
}
