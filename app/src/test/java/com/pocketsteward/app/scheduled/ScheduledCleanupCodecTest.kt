package com.pocketsteward.app.scheduled

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScheduledCleanupCodecTest {
    @Test
    fun settingsRoundTrip() {
        val input = ScheduledCleanupSettings(
            enabled = true,
            intervalHours = 48,
            roots = listOf("/storage/emulated/0/Download", "/storage/emulated/0/Documents"),
        )

        assertThat(ScheduledCleanupCodec.decode(ScheduledCleanupCodec.encode(input)))
            .isEqualTo(input)
    }

    @Test
    fun malformedInputFallsBackSafely() {
        val decoded = ScheduledCleanupCodec.decode("1|wat|")
        assertThat(decoded.enabled).isTrue()
        assertThat(decoded.intervalHours).isEqualTo(24)
        assertThat(decoded.roots).isEmpty()
    }
}
