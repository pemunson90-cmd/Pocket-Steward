package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.ScanStatus
import org.junit.Test

class ScanResumePolicyTest {
    @Test
    fun pausedAndRunningCheckpointsResume() {
        assertThat(ScanStatus.RUNNING.isResumable()).isTrue()
        assertThat(ScanStatus.PAUSED.isResumable()).isTrue()
    }

    @Test
    fun completedAndFailedCheckpointsRestartCleanly() {
        assertThat(ScanStatus.COMPLETED.isResumable()).isFalse()
        assertThat(ScanStatus.FAILED.isResumable()).isFalse()
    }
}
