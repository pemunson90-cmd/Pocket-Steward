package com.pocketsteward.app.image

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageAnalysisGateTest {
    @Test fun admissionWaitsForLateSdkCompletionAndReleasesExactlyOnce() = runTest {
        val gate = ImageAnalysisGate()
        val first = gate.acquire()
        val task = TaskCompletionSource<Unit>()
        var disposals = 0
        first.releaseAfter(task.task) { disposals++ }
        first.releaseAfter(null) { error("No second disposal") }
        val next = async { gate.acquire() }
        runCurrent()
        assertThat(next.isCompleted).isFalse()
        assertThat(disposals).isEqualTo(0)
        task.setResult(Unit)
        runCurrent()
        assertThat(disposals).isEqualTo(1)
        next.await().releaseAfter(null) { }
        gate.acquire().releaseAfter(null) { }
    }
    @Test fun cancelledWaiterDoesNotReleaseAnotherCallsLease() = runTest {
        val gate = ImageAnalysisGate()
        val first = gate.acquire()
        val cancelled = async { gate.acquire() }
        runCurrent()
        cancelled.cancel()
        val next = async { gate.acquire() }
        runCurrent()
        assertThat(next.isCompleted).isFalse()
        first.releaseAfter(null) { }
        next.await().releaseAfter(null) { }
    }
}
