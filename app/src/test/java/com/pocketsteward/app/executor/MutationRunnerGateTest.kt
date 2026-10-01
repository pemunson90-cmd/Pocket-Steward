package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MutationRunnerGateTest {
    @Test fun recoveryWaitsUntilMutationFinishes() = runTest {
        val gate = MutationRunnerGate()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val mutation = launch { gate.run { events += "pending"; finish.await(); events += "committed" } }
        runCurrent()
        val recovery = launch { gate.run { events += "recovery" } }
        runCurrent()
        assertThat(events).containsExactly("pending")
        finish.complete(Unit)
        mutation.join(); recovery.join()
        assertThat(events).containsExactly("pending", "committed", "recovery").inOrder()
    }
    @Test fun cancellationReleasesGateForRecovery() = runTest {
        val gate = MutationRunnerGate()
        val mutation = launch { gate.run { CompletableDeferred<Unit>().await() } }
        runCurrent()
        mutation.cancel(); mutation.join()
        var recovered = false
        gate.run { recovered = true }
        assertThat(recovered).isTrue()
    }
    @Test fun cancelledWaiterDoesNotBlockTheNextRunner() = runTest {
        val gate = MutationRunnerGate()
        val finish = CompletableDeferred<Unit>()
        val running = launch { gate.run { finish.await() } }
        runCurrent()
        val waiter = launch { gate.run { error("cancelled waiter executed") } }
        runCurrent()
        waiter.cancel(); waiter.join()
        finish.complete(Unit); running.join()
        assertThat(gate.run { 42 }).isEqualTo(42)
    }
}
