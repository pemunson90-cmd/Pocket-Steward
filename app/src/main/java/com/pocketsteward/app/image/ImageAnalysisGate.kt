package com.pocketsteward.app.image

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation must not admit another bitmap while an SDK task still holds the previous one. */
internal class ImageAnalysisGate {
    private val permits = Semaphore(1)
    suspend fun acquire(): Lease { permits.acquire(); return Lease() }
    inner class Lease {
        private val handedOff = AtomicBoolean(false)
        fun releaseAfter(task: Task<*>?, dispose: () -> Unit) {
            if (!handedOff.compareAndSet(false, true)) return
            val release = { try { dispose() } finally { permits.release() }; Unit }
            if (task == null || task.isComplete) release()
            else task.addOnCompleteListener(Executor { it.run() }) { release() }
        }
    }
}
