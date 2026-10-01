package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxRefreshQueueTest {
    @Test fun sixteenThousandNoticesProduceOneTrailingRefresh() = runTest {
        var refreshes = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, {}, { true },
            { refreshes++ }, { throw AssertionError(it) })
        repeat(16_000) { queue.notice() }
        runCurrent()
        advanceTimeBy(2_999)
        runCurrent()
        assertThat(refreshes).isEqualTo(0)
        advanceTimeBy(1)
        runCurrent()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test fun anotherBurstWaitsTwoMinutesAfterThePreviousRefresh() = runTest {
        var refreshes = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, {}, { true },
            { refreshes++ }, { throw AssertionError(it) })
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertThat(refreshes).isEqualTo(1)
        repeat(16_000) { queue.notice() }
        runCurrent(); advanceTimeBy(119_999); runCurrent()
        assertThat(refreshes).isEqualTo(1)
        advanceTimeBy(1); runCurrent()
        assertThat(refreshes).isEqualTo(2)
    }

    @Test fun eventsDuringAnActiveTaskWaitAndThenRefreshOnce() = runTest {
        val idle = CompletableDeferred<Unit>()
        var refreshes = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, { idle.await() }, { true },
            { refreshes++ }, { throw AssertionError(it) })
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertThat(refreshes).isEqualTo(0)
        repeat(16_000) { queue.notice() }
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        idle.complete(Unit); runCurrent()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test fun disablingObservationCancelsTheWaitingRefresh() = runTest {
        val idle = CompletableDeferred<Unit>()
        var refreshes = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, { idle.await() }, { true },
            { refreshes++ }, { throw AssertionError(it) })
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        queue.cancelPending(); runCurrent()
        idle.complete(Unit); runCurrent(); advanceTimeBy(120_000); runCurrent()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test fun revokedPermissionIsCheckedAfterWaitingForTasks() = runTest {
        val idle = CompletableDeferred<Unit>()
        var allowed = true
        var refreshes = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, { idle.await() }, { allowed },
            { refreshes++ }, { throw AssertionError(it) })
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        allowed = false
        idle.complete(Unit); runCurrent()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test fun aRefreshFailureDoesNotDisableFutureObservation() = runTest {
        var attempts = 0
        var failures = 0
        val queue = InboxRefreshQueue(backgroundScope, { testScheduler.currentTime }, {}, { true },
            { if (++attempts == 1) error("Temporary scheduler failure") }, { failures++ })
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        queue.notice(); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertThat(attempts).isEqualTo(2)
        assertThat(failures).isEqualTo(1)
    }
}
