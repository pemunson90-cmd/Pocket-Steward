package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import org.junit.Test

class FilingTaskInventoryTest {
    private val operations = listOf(
        PlannedOperation.CreateDirectory(FileRef.Direct("/Documents"), "Lilith", "home"),
        PlannedOperation.Move(FileRef.Direct("/Download/Lilith.md"), FileRef.Direct("/Documents/Lilith/Lilith.md"), "project match"),
    )
    private fun inventory() = FilingTaskInventory(listOf(
        FilingTaskItem("D:/Download/Lilith.md", "Lilith.md", false, FilingOutcome.DESTINATION, "project match", 1, "D:/Documents/Lilith/Lilith.md"),
        FilingTaskItem("D:/Download/Uncertain/unknown.txt", "unknown.txt", false, FilingOutcome.RETAINED_UNCERTAIN, "no match"),
        FilingTaskItem("D:/Download/keep.txt", "keep.txt", false, FilingOutcome.KEEP, "keep"),
    ), FilingIntakeSnapshot(listOf(FilingIntakeRoot("D:/Download", listOf("D:/Download/Lilith.md", "D:/Download/keep.txt", "D:/Download/Uncertain"))), 123), 5)

    @Test fun durableTaskKeepsEveryDispositionAndIntakeAfterRestart() {
        val expected = inventory()
        val encoded = DurablePlanCodec.encode("Inbox filing", operations, filingInventory = expected)
        assertThat(DurablePlanCodec.decodeOrNull(encoded)?.filingInventory).isEqualTo(expected)
        assertThat(DurablePlanCodec.decodeOrNull(encoded)?.operations).isEqualTo(operations)
    }

    @Test fun mismatchedInventoryCannotAuthorizeAnotherSourceOrDestination() {
        val expected = inventory()
        val badSource = expected.copy(items = expected.items.map { if (it.operationSequence != null) it.copy(source = "D:/secret") else it })
        val badDestination = expected.copy(items = expected.items.map { if (it.operationSequence != null) it.copy(destination = "D:/elsewhere") else it })
        listOf(badSource, badDestination).forEach { bad ->
            assertThat(runCatching { DurablePlanCodec.encode("Inbox", operations, filingInventory = bad) }.isFailure).isTrue()
            val encoded = DurablePlanCodec.encode("Inbox", operations) + "@psfiling\t${FilingTaskInventoryCodec.encode(bad)}\n"
            assertThat(DurablePlanCodec.decodeOrNull(encoded)).isNull()
        }
    }

    @Test fun missingDuplicateOrPendingSourcesRejectIncompleteInventory() {
        val expected = inventory()
        listOf(expected.copy(items = expected.items.drop(1)),
            expected.copy(items = expected.items + expected.items.first()),
            expected.copy(items = expected.items.map { if (it.outcome == FilingOutcome.KEEP) it.copy(outcome = FilingOutcome.NEEDS_DECISION) else it }),
        ).forEach { assertThat(runCatching { it.validate(operations) }.isFailure).isTrue() }
    }

    @Test fun duplicateMachineInventoryRejectsTheWholeTask() {
        val encoded = DurablePlanCodec.encode("Inbox", operations, filingInventory = inventory())
        val line = encoded.lineSequence().first { it.startsWith("@psfiling\t") }
        assertThat(DurablePlanCodec.decodeOrNull(encoded + line + "\n")).isNull()
    }

    @Test fun sixteenThousandSourcesRoundTripWithoutChangingTheirApprovedSequences() {
        val ops = List(16_000) { n -> PlannedOperation.Move(FileRef.Direct("/Download/$n.txt"), FileRef.Direct("/Documents/$n.txt"), "reviewed") }
        val items = ops.mapIndexed { sequence, op -> FilingTaskItem(FileRefJournalCodec.encode(op.source), "$sequence.txt", false, FilingOutcome.DESTINATION, "reviewed", sequence, FileRefJournalCodec.encode(op.destination)) }
        val expected = FilingTaskInventory(items, null, 0)
        val encoded = DurablePlanCodec.encode("16k review", ops, filingInventory = expected)
        assertThat(DurablePlanCodec.decodeOrNull(encoded)?.filingInventory).isEqualTo(expected)
    }
}
