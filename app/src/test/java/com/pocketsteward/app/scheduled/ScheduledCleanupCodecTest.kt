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
    @Test
    fun pendingSuggestionRoundTripsMultipleRoots() {
        val input = PendingCleanupSuggestion(
            createdAtEpochMs = 123456789L,
            roots = listOf(
                "/storage/emulated/0/Download",
                "content://provider/tree/root/document/root%2FWriting",
            ),
            newFileCount = 43,
            obviousMatchCount = 17,
        )

        val decoded = PendingCleanupSuggestionCodec.decode(
            PendingCleanupSuggestionCodec.encode(input),
        )

        assertThat(decoded).isEqualTo(input)
    }

    @Test
    fun malformedPendingSuggestionFailsClosed() {
        assertThat(PendingCleanupSuggestionCodec.decode("garbage")).isNull()
        assertThat(PendingCleanupSuggestionCodec.decode("1|nope|4|2|abc")).isNull()
    }

    @Test
    fun pendingSuggestionV2RoundTripsExactNewFileRefs() {
        val input = PendingCleanupSuggestion(
            createdAtEpochMs = 987654321L,
            roots = listOf("/storage/emulated/0/Download"),
            newFileCount = 3,
            obviousMatchCount = 2,
            newFileRefs = listOf(
                "/storage/emulated/0/Download/new-a.pdf",
                "/storage/emulated/0/Download/new b.jpg",
                "content://provider/tree/root/document/root%2Fnew-c.md",
            ),
        )

        val decoded = PendingCleanupSuggestionCodec.decode(
            PendingCleanupSuggestionCodec.encode(input),
        )

        assertThat(decoded).isEqualTo(input)
    }

    @Test
    fun legacyPendingSuggestionV1StillDecodesWithoutExactRefs() {
        val root = "/storage/emulated/0/Download"
        val encodedRoot = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(root.toByteArray(Charsets.UTF_8))
        val decoded = PendingCleanupSuggestionCodec.decode(
            "1|123|43|17|$encodedRoot",
        )

        assertThat(decoded).isEqualTo(
            PendingCleanupSuggestion(
                createdAtEpochMs = 123L,
                roots = listOf(root),
                newFileCount = 43,
                obviousMatchCount = 17,
                newFileRefs = emptyList(),
            ),
        )
    }

    @Test
    fun pendingSuggestionDeduplicatesExactRefs() {
        val input = PendingCleanupSuggestion(
            createdAtEpochMs = 1L,
            roots = listOf("/root"),
            newFileCount = 2,
            obviousMatchCount = 1,
            newFileRefs = listOf("/root/a.txt", "/root/a.txt"),
        )

        val decoded = PendingCleanupSuggestionCodec.decode(
            PendingCleanupSuggestionCodec.encode(input),
        )

        assertThat(decoded!!.newFileRefs).containsExactly("/root/a.txt")
    }

}
