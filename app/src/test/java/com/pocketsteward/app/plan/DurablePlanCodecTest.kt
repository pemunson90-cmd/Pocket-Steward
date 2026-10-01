package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.report.TaskManifest
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class DurablePlanCodecTest {
    @Test
    fun folderSnapshotSurvivesPauseAndResumeEncoding() {
        val operation = PlannedOperation.Move(FileRef.Direct("/Download/Lilith"), FileRef.Direct("/Documents/Lilith"), "keep folder intact")
        val snapshot = SourcePrecondition(1234L, 99L, "folder-digest", 12)
        val encoded = DurablePlanCodec.encode("Folder filing", listOf(operation), mapOf(0 to snapshot))
        assertThat(DurablePlanCodec.decodeOrNull(encoded)?.sourcePreconditions).containsExactly(0, snapshot)
    }

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
            PlannedOperation.Copy(
                FileRef.Direct("/sd/Download/source.txt"),
                FileRef.Direct("/sd/Download/Café 📚/source copy.txt"),
                "copy reason",
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
    @Test
    fun sourcePreconditionsRoundTripInVersionTwoPlan() {
        val operations = listOf(
            PlannedOperation.Move(
                FileRef.Direct("/sd/Download/a.txt"),
                FileRef.Direct("/sd/Documents/a.txt"),
                "move",
            ),
        )
        val expected = mapOf(
            0 to SourcePrecondition(
                sizeBytes = 42L,
                modifiedAtEpochMs = 123456789L,
            ),
        )

        val encoded = DurablePlanCodec.encode("Move safely", operations, expected)
        val decoded = DurablePlanCodec.decodeOrNull(encoded)

        assertThat(decoded?.sourcePreconditions).isEqualTo(expected)
        assertThat(decoded?.operations).containsExactlyElementsIn(operations).inOrder()
    }

    @Test
    fun versionTwoFilePreconditionsRemainReadable() {
        val operations = listOf(PlannedOperation.Move(FileRef.Direct("/a"), FileRef.Direct("/b"), "move"))
        val expected = SourcePrecondition(42L, 123L)
        val v2 = DurablePlanCodec.encode("Legacy file", operations, mapOf(0 to expected))
            .replace("@psplan\t3", "@psplan\t2")
            .replace("@pspre\t0\t42\t123\tnull\t0", "@pspre\t0\t42\t123")
        assertThat(DurablePlanCodec.decodeOrNull(v2)?.sourcePreconditions).containsExactly(0, expected)
    }

    @Test
    fun versionOneDurablePlanStillDecodesAfterPreconditionsWereAdded() {
        val operations = listOf(
            PlannedOperation.Move(
                FileRef.Direct("/sd/Download/a.txt"),
                FileRef.Direct("/sd/Documents/a.txt"),
                "move",
            ),
        )
        val v1 = DurablePlanCodec.encode("Legacy durable", operations)
            .replace("@psplan\t3", "@psplan\t1")

        val decoded = DurablePlanCodec.decodeOrNull(v1)

        assertThat(decoded).isEqualTo(
            DurablePlan(
                goal = "Legacy durable",
                operations = operations,
                sourcePreconditions = emptyMap(),
            ),
        )
        assertThat(DurablePlanCodec.isDurable(v1)).isTrue()
    }

}
