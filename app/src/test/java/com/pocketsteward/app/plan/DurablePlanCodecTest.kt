package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.report.TaskManifest
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import org.junit.Test

class DurablePlanCodecTest {
    @Test fun selectedTreeLocationSurvivesRestartAndVersionFourRemainsReadable() {
        val source = FileRef.Saf("content://provider/tree/grant/document/retained-id")
        val destination = FileRef.Child(FileRef.Saf("content://provider/tree/grant/document/home-id"), "bundle")
        val original = FileRefJournalCodec.encode(FileRef.Child(FileRef.Saf("content://provider/tree/grant/document/inbox-id"), "bundle"))
        val expected = SourcePrecondition(42, 123, "a".repeat(64), 2, original)
        val encoded = DurablePlanCodec.encode("Move folder", listOf(PlannedOperation.Move(source, destination, "reviewed")), mapOf(0 to expected))
        assertThat(DurablePlanCodec.decodeOrNull(encoded)!!.sourcePreconditions).containsExactly(0, expected)
        assertThat(SourcePreconditions.matches(expected, expected.copy(location = FileRefJournalCodec.encode(destination)))).isFalse()
        val legacy = encoded.lineSequence().map { line -> when {
            line == "@psplan\t5" -> "@psplan\t4"
            line.startsWith("@pspre\t") -> line.split('\t').take(6).joinToString("\t")
            else -> line
        } }.joinToString("\n")
        assertThat(DurablePlanCodec.decodeOrNull(legacy)!!.sourcePreconditions).containsExactly(0, expected.copy(location = null))
    }
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
            .replace("@psplan\t5", "@psplan\t2")
            .replace("@pspre\t0\t42\t123\tnull\t0\tnull", "@pspre\t0\t42\t123")
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
            .replace("@psplan\t5", "@psplan\t1")

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

    @Test fun goalAndReasonCannotInjectAnUnapprovedOperation() {
        val extra = PlannedOperation.Trash(FileRef.Direct("/unapproved"), "injected")
        val injectedLine = DurablePlanCodec.encode("Extra", listOf(extra)).lineSequence().single { it.startsWith("@psop\t") }.replace("@psop\t0", "@psop\t1")
        val goal = "Organize\n$injectedLine"
        val operation = PlannedOperation.Move(FileRef.Direct("/a"), FileRef.Direct("/b"), "Reason\n$injectedLine")
        val decoded = DurablePlanCodec.decodeOrNull(DurablePlanCodec.encode(goal, listOf(operation)))
        assertThat(decoded?.operations).containsExactly(operation)
        assertThat(decoded?.goal).isEqualTo(goal)
    }

    @Test fun legacyRecordInjectedBeforeRealOperationFailsClosed() {
        val operation = PlannedOperation.Move(FileRef.Direct("/a"), FileRef.Direct("/b"), "Reason")
        val actual = DurablePlanCodec.encode("Goal", listOf(operation)).lineSequence().single { it.startsWith("@psop\t") }
        val injected = DurablePlanCodec.encode("Goal", listOf(PlannedOperation.Trash(FileRef.Direct("/unapproved"), "injected"))).lineSequence().single { it.startsWith("@psop\t") }.replace("@psop\t0", "@psop\t1")
        val legacy = "Goal\n@psplan\t3\n0\tMOVE\tReason\n$injected\n$actual\n"
        assertThat(DurablePlanCodec.decodeOrNull(legacy)).isNull()
        assertThat(DurablePlanCodec.decodeOrNull("Goal\n$injected\n@psplan\t3\n$actual\n")).isNull()
    }

    @Test fun duplicatePreconditionsFailClosedAndDigestTokensCannotInjectRecords() {
        val operation = PlannedOperation.Move(FileRef.Direct("/a"), FileRef.Direct("/b"), "Reason")
        val condition = SourcePrecondition(10, 20, "digest\n@psop\t1\tpayload", 1)
        val encoded = DurablePlanCodec.encode("Goal", listOf(operation), mapOf(0 to condition))
        assertThat(DurablePlanCodec.decodeOrNull(encoded)?.sourcePreconditions).containsExactly(0, condition)
        val precondition = encoded.lineSequence().single { it.startsWith("@pspre\t") }
        assertThat(DurablePlanCodec.decodeOrNull(encoded + precondition + "\n")).isNull()
    }
    @Test fun humanReportsRecoverExactMultilineReasonsFromStructuredRecords() {
        val operation = PlannedOperation.Move(FileRef.Direct("/a"), FileRef.Direct("/b"), "Line one\nLine two\twith tab and \\ path")
        assertThat(TaskManifest.reasonsBySequence(DurablePlanCodec.encode("Goal", listOf(operation)))).containsExactly(0, operation.reason)
    }

}
