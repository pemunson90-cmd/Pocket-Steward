package com.pocketsteward.app.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StorageCapacityPolicyTest {
    @Test
    fun exactAvailableCapacityIsAllowed() {
        assertThat(StorageCapacityPolicy.canFit(1_024L, 1_024L)).isTrue()
    }

    @Test
    fun oneByteShortFailsClosed() {
        assertThat(StorageCapacityPolicy.canFit(1_025L, 1_024L)).isFalse()
    }

    @Test
    fun invalidNegativeValuesFailClosed() {
        assertThat(StorageCapacityPolicy.canFit(-1L, 100L)).isFalse()
        assertThat(StorageCapacityPolicy.canFit(100L, -1L)).isFalse()
    }

    @Test
    fun failureMessageStatesRequiredAndAvailableBytes() {
        val message = StorageCapacityPolicy.failureMessage(2_000L, 1_000L)
        assertThat(message).contains("2000")
        assertThat(message).contains("1000")
    }
}
