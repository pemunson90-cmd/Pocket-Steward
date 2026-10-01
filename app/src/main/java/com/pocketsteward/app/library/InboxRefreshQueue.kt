package com.pocketsteward.app.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One pending trailing refresh, even when thousands of filesystem notices arrive. */
internal class InboxRefreshQueue(
    scope: CoroutineScope,
    private val now: () -> Long,
    private val awaitIdle: suspend () -> Unit,
    private val allowed: suspend () -> Boolean,
    private val refresh: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val generation = AtomicLong(0)
    private val notices = Channel<Long?>(Channel.CONFLATED)
    private var lastQueuedAt: Long? = null

    init {
        scope.launch {
            notices.receiveAsFlow().collectLatest { expected ->
                if (expected == null || expected != generation.get()) return@collectLatest
                try {
                    delay(InboxObservationPolicy.QUIET_PERIOD_MS)
                    delay(InboxObservationPolicy.remainingDelay(now(), lastQueuedAt))
                    awaitIdle()
                    if (!allowed() || expected != generation.get()) return@collectLatest
                    refresh()
                    lastQueuedAt = now()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
        }
    }

    fun notice() { notices.trySend(generation.get()) }

    fun cancelPending() {
        generation.incrementAndGet()
        notices.trySend(null)
    }
}
