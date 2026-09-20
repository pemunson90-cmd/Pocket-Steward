package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.report.TaskManifest
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class DurablePlanCodecTest {
    @Test
    fun roundTripsEveryOperationType() {
        val operations = listOf(
            PlannedOperation.CreateDirectory(
                FileRef.Direct("/sd/Download"),
                "Café 📚",
                "create destination",
            ),
            PlannedOperation.Move(
                FileRef.Direct("/sd/Download/a.txt"),
                FileRef.Direct("/sd/Download/Café 📚/a.txt"),
                "move reason",
            ),
            PlannedOperation.Rename(
                FileRef.Direct("/sd/Download/b.txt"),
                "renamed b.txt",
                "rename reason",
            ),
            PlannedOperation.Trash(
                FileRef.Direct("/sd/Download/c.txt"),
                "trash reason",
                sourceFingerprint = "abc123",
            ),
            PlannedOperation.WriteTextFile(
                FileRef.Direct("/sd/Download"),
                "note.md",
                "line one\nline two\twith tab",
                "write reason",
            ),
        )

        val encoded = DurablePlanCodec.encode("Goal ✓", operations)
        val decoded = DurablePlanCodec.decodeOrNull(encoded)

        assertThat(decoded).isEqualTo(DurablePlan("Goal ✓", operations))
    }

    @Test
    fun legacyPlanIsNotPretendedToBeResumable() {
        val old = "Smart cleanup\n0\tMOVE\tExtension maps to document\n"

        assertThat(DurablePlanCodec.decodeOrNull(old)).isNull()
        assertThat(DurablePlanCodec.isDurable(old)).isFalse()
    }

    @Test
    fun corruptPayloadFailsClosed() {
        val corrupt = "Goal\n@psplan\t1\n0\tMOVE\treason\n@psop\t0\tnot-base64\n"

        assertThat(DurablePlanCodec.decodeOrNull(corrupt)).isNull()
    }

    @Test
    fun humanManifestReasonParserIgnoresDurablePayloadLines() {
        val operations = listOf(
            PlannedOperation.Move(
                FileRef.Direct("/a"),
                FileRef.Direct("/b"),
                "because this belongs there",
            ),
        )

        val encoded = DurablePlanCodec.encode("Goal", operations)

        assertThat(TaskManifest.reasonsBySequence(encoded))
            .containsExactly(0, "because this belongs there")
    }
}
