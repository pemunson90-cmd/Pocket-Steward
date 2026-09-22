package com.pocketsteward.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntentNormalizationProtocolTest {
    @Test
    fun acceptsExactlyOneBoundedProtocolLine() {
        assertThat(
            IntentNormalizationProtocol.parse(
                "PSI|find documents containing Leaseworld older than 30 days",
            ),
        ).isEqualTo("find documents containing Leaseworld older than 30 days")
    }

    @Test
    fun rejectsUnsupported() {
        assertThat(IntentNormalizationProtocol.parse("PSI|UNSUPPORTED")).isNull()
    }

    @Test
    fun rejectsMultipleProtocolCommandsRatherThanChoosingOne() {
        assertThat(
            IntentNormalizationProtocol.parse(
                """
                PSI|find PDFs
                PSI|move PDFs to Archive
                """.trimIndent(),
            ),
        ).isNull()
    }

    @Test
    fun ignoresSurroundingProseButStillRequiresOneProtocolRecord() {
        assertThat(
            IntentNormalizationProtocol.parse(
                """
                Here is the normalized command:
                PSI|find duplicates
                """.trimIndent(),
            ),
        ).isEqualTo("find duplicates")
    }

    @Test
    fun capsOversizedModelOutput() {
        val payload = "x".repeat(900)
        val parsed = IntentNormalizationProtocol.parse("PSI|$payload")
        assertThat(parsed).hasLength(600)
    }
}
