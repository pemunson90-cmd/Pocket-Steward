package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.*
import com.pocketsteward.app.filing.*
import com.pocketsteward.app.plan.*
import com.pocketsteward.app.storage.*
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FilingReconciliationTest {
    private val source = FileRef.Direct("/Download/Lilith.md")
    private val destination = FileRef.Direct("/Documents/Lilith.md")
    private fun plan(outcome: FilingOutcome = FilingOutcome.DESTINATION): DurablePlan {
        val op = if (outcome == FilingOutcome.COPY_RETAINED) PlannedOperation.Copy(source, destination, "reviewed") else PlannedOperation.Move(source, destination, "reviewed")
        val items = listOf(FilingTaskItem(FileRefJournalCodec.encode(source), "Lilith.md", false, outcome, "reviewed", 0, FileRefJournalCodec.encode(destination)),
            FilingTaskItem("D:/Download/keep.txt", "keep.txt", false, FilingOutcome.KEEP, "keep here"),
            FilingTaskItem("D:/Download/Uncertain/a.txt", "a.txt", false, FilingOutcome.RETAINED_UNCERTAIN, "unsure"))
        val intake = FilingIntakeSnapshot(listOf(
            FilingIntakeRoot("D:/Download", listOf("D:/Download/Lilith.md", "D:/Download/keep.txt", "D:/Download/Uncertain", "D:/Download/protected")),
            FilingIntakeRoot("D:/Download/Uncertain", listOf("D:/Download/Uncertain/a.txt")),
        ), 1)
        return DurablePlan("Inbox", listOf(op), mapOf(0 to SourcePrecondition(12, 99)), FilingTaskInventory(items, intake, 4))
    }
    private fun record(status: MutationStatus = MutationStatus.COMMITTED, copy: Boolean = false) = MutationRecord(
        taskRunId = 1, sequence = 0, operationType = if (copy) MutationOperationType.COPY else MutationOperationType.MOVE,
        sourceBefore = FileRefJournalCodec.encode(source), destinationAfter = FileRefJournalCodec.encode(destination),
        sourceFingerprint = null, status = status, executedAt = 1, undoState = UndoState.AVAILABLE, undoAttemptedAt = null, error = null, undoError = null,
    )
    private fun gateway(vararg extra: String) = ReadOnlyGateway(setOf("/Documents/Lilith.md", "/Download/keep.txt", "/Download/Uncertain/a.txt", "/Download/protected", "/Download/Uncertain") + extra)

    @Test fun verifiesFiledHeldAndUncertainAndSeparatesLaterArrivalsFromUnreviewed() = runTest {
        val report = requireNotNull(FilingReconciler.check(plan(), listOf(record()), gateway("/Download/new.txt")))
        assertThat(report.count(FilingLocationState.FILED)).isEqualTo(1)
        assertThat(report.count(FilingLocationState.KEPT)).isEqualTo(1)
        assertThat(report.count(FilingLocationState.RETAINED_UNCERTAIN)).isEqualTo(1)
        assertThat(report.roots.first().newArrivals).containsExactly("D:/Download/new.txt")
        assertThat(report.roots.first().unreviewed).containsExactly("D:/Download/protected")
        assertThat(report.needsAttention).isEqualTo(0)
    }

    @Test fun pendingFailedAndMissingJournalNeverBecomeSuccessfulFromLocationAlone() = runTest {
        listOf(MutationStatus.PENDING, MutationStatus.NEEDS_REVIEW).forEach { status ->
            assertThat(FilingReconciler.check(plan(), listOf(record(status)), gateway())?.locations?.first()?.state).isEqualTo(FilingLocationState.RECOVERY_REQUIRED)
        }
        assertThat(FilingReconciler.check(plan(), listOf(record(MutationStatus.FAILED)), gateway())?.locations?.first()?.state).isEqualTo(FilingLocationState.FAILED)
        assertThat(FilingReconciler.check(plan(), emptyList(), gateway())?.locations?.first()?.state).isEqualTo(FilingLocationState.NOT_RUN)
    }

    @Test fun checksUndoCopyMissingAndChangedDestinations() = runTest {
        assertThat(FilingReconciler.check(plan(), listOf(record(MutationStatus.UNDONE)), gateway(source.rawValue()))?.locations?.first()?.state).isEqualTo(FilingLocationState.UNDONE)
        assertThat(FilingReconciler.check(plan(), listOf(record()), gateway(source.rawValue()))?.locations?.first()?.state).isEqualTo(FilingLocationState.SOURCE_STILL_PRESENT)
        assertThat(FilingReconciler.check(plan(FilingOutcome.COPY_RETAINED), listOf(record(copy = true)), gateway(source.rawValue()))?.locations?.first()?.state).isEqualTo(FilingLocationState.COPY_PRESENT)
        val missing = ReadOnlyGateway(setOf("/Download/keep.txt"))
        assertThat(FilingReconciler.check(plan(), listOf(record()), missing)?.locations?.first()?.state).isEqualTo(FilingLocationState.DESTINATION_MISSING)
        val changed = ReadOnlyGateway(setOf(destination.rawValue()), bytes = 99)
        assertThat(FilingReconciler.check(plan(), listOf(record()), changed)?.locations?.first()?.state).isEqualTo(FilingLocationState.CHANGED)
    }

    @Test fun inaccessibleListingDoesNotPretendThereAreZeroNewArrivalsAndCancellationPropagates() = runTest {
        val inaccessible = object : StorageGateway by gateway() {
            override suspend fun listChildren(directory: FileRef): List<FileEntry> = throw SecurityException("revoked")
        }
        val report = requireNotNull(FilingReconciler.check(plan(), listOf(record()), inaccessible))
        assertThat(report.roots.all { it.error != null }).isTrue()
        val cancelled = object : StorageGateway by gateway() {
            override suspend fun verifyExists(ref: FileRef): Boolean = throw CancellationException("pause")
        }
        assertThat(runCatching { FilingReconciler.check(plan(), listOf(record()), cancelled) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    private open class ReadOnlyGateway(private val paths: Set<String>, private val bytes: Long = 12) : StorageGateway {
        override suspend fun rootOf(scope: StorageScope): FileRef = error("no authority")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = paths.filter { it.substringBeforeLast('/') == directory.rawValue() }
            .map { FileEntry(FileRef.Direct(it), it.substringAfterLast('/'), it.endsWith("Uncertain") || it.endsWith("protected"), directory) }
        override suspend fun stat(ref: FileRef) = FileMetadata(ref, ref.rawValue().substringAfterLast('/'), "txt", "text/plain", bytes, null, 99, false, false)
        override suspend fun exists(ref: FileRef) = ref.rawValue() in paths
        override suspend fun openRead(ref: FileRef): InputStream = error("No content reads are needed for these file observations")
        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult = error("Read-only")
        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult = error("Read-only")
        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun move(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun rename(source: FileRef, newName: String): MutationResult = error("Read-only")
        override suspend fun trashDestination(source: FileRef): FileRef = error("Read-only")
        override suspend fun trash(source: FileRef): MutationResult = error("Read-only")
        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult = error("Read-only")
    }
}
