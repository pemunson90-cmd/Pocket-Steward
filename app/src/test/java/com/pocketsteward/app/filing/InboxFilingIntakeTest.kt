package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class InboxFilingIntakeTest {
    @Test fun checkpointIntakeIncludesOnlyImmediateUnitsAndPreservesFolderBundles() {
        val inbox = "/storage/emulated/0/Download"
        val checkpoint = "$inbox/Uncertain"
        val records = listOf(
            record(checkpoint, inbox, "Uncertain", true),
            record("$inbox/new.txt", inbox),
            record("$checkpoint/notes.txt", checkpoint),
            record("$checkpoint/Lilith", checkpoint, "Lilith", true),
            record("$checkpoint/Lilith/cover.png", "$checkpoint/Lilith"),
            record("$inbox/Other/Uncertain", "$inbox/Other", "Uncertain", true),
            record("$inbox/Other/Uncertain/unrelated.txt", "$inbox/Other/Uncertain"),
        )
        val selection = InboxFilingIntake.select(records, setOf(inbox), true, true)
        assertThat(selection.records.map { it.stableRef }).containsExactly("$checkpoint/notes.txt", "$checkpoint/Lilith")
        assertThat(selection.retainedUncertainSourceRefs).containsExactly("$checkpoint/notes.txt", "$checkpoint/Lilith")
        assertThat(InboxFilingIntake.select(records, setOf(inbox), false, true).records.map { it.stableRef })
            .containsExactly("$inbox/new.txt")
    }

    @Test fun selectedTreeUsesParentUrisInsteadOfPathConcatenation() {
        val root = "content://provider/document/42"
        val checkpoint = "content://provider/document/99"
        val file = "content://provider/document/123"
        val records = listOf(record(checkpoint, root, "uNcErTaIn", true), record(file, checkpoint))
        val selection = InboxFilingIntake.select(records, setOf(root), true, false)
        assertThat(selection.records.map { it.stableRef }).containsExactly(file)
        assertThat(selection.retainedUncertainSourceRefs).containsExactly(file)
    }

    @Test fun unresolvedCheckpointFileGeneratesNoNestedCheckpointOrSelectedMove() {
        val source = "/storage/emulated/0/Download/Uncertain/unknown.txt"
        val decision = unresolved(source, "/storage/emulated/0/Download/Uncertain")
        val plan = InboxFilingPlanAdapter.build(
            InboxFilingResult(listOf(decision)), FileRef.Direct("/storage/emulated/0"), emptySet(), setOf(source),
        )
        assertThat(plan.operations).isEmpty()
        assertThat(plan.defaultSelectedSourceRefs).isEmpty()
        assertThat(plan.presentation.unresolved.single().sourceRef).isEqualTo(source)
        assertThat(plan.presentation.checkpointCount).isEqualTo(0)
    }

    @Test fun selectedTreeUnresolvedCheckpointFileStaysInPlace() {
        val source = "content://provider/document/123"
        val decision = unresolved(source, "content://provider/document/99")
        val plan = InboxFilingSafPlanAdapter.build(
            InboxFilingResult(listOf(decision)), FileRef.Saf("content://provider/document/42"), emptyMap(), "Downloads", setOf(source),
        )
        assertThat(plan.operations).isEmpty()
        assertThat(plan.presentation.unresolved.single().sourceRef).isEqualTo(source)
    }

    @Test fun resolvedCheckpointFileCanLeaveWhileUnknownRemainsAndNewLooseFileGetsCheckpointed() {
        val inbox = "/storage/emulated/0/Download"
        val source = "$inbox/Uncertain/Lilith-notes.txt"
        val home = ProjectHomeCandidate("Lilith", "/storage/emulated/0/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val known = unresolved(source, "$inbox/Uncertain").copy(
            projectName = "Lilith", projectHome = home, destinationDirectory = "${home.path}/Notes", confidence = FilingConfidence.STRONG,
        )
        val unknown = unresolved("$inbox/Uncertain/unknown.txt", "$inbox/Uncertain")
        val newLoose = unresolved("$inbox/new.txt", inbox)
        val plan = InboxFilingPlanAdapter.build(
            InboxFilingResult(listOf(known, unknown, newLoose)), FileRef.Direct("/storage/emulated/0"),
            setOf(home.path, "${home.path}/Notes", "$inbox/Uncertain"), setOf(source, unknown.artifact.stableRef),
        )
        val moves = plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves.map { it.source.rawValue() }).containsExactly(source, newLoose.artifact.stableRef)
        assertThat(moves.first { it.source.rawValue() == source }.destination.rawValue()).isEqualTo("${home.path}/Notes/Lilith-notes.txt")
        assertThat(plan.defaultSelectedSourceRefs).containsExactly(source, newLoose.artifact.stableRef)
    }

    @Test fun largeCheckpointSelectionDeduplicatesOverlappingInventory() {
        val root = "/storage/emulated/0/Download"
        val checkpoint = "$root/Uncertain"
        val files = (1..16_000).map { record("$checkpoint/$it.txt", checkpoint) }
        val result = InboxFilingIntake.select(listOf(record(checkpoint, root, "Uncertain", true)) + files + files.take(10), setOf(root), true, true)
        assertThat(result.records).hasSize(16_000)
        assertThat(result.retainedUncertainSourceRefs).hasSize(16_000)
    }

    @Test fun selectedCheckpointRootDoesNotRequireItsParentToBeIndexed() {
        val checkpoint = "/Download/Uncertain"
        val file = record("$checkpoint/a.txt", checkpoint)
        val selected = InboxFilingIntake.select(listOf(file), setOf(checkpoint), true, true, setOf(checkpoint))
        assertThat(selected.records).containsExactly(file)
        assertThat(selected.retainedUncertainSourceRefs).containsExactly(file.stableRef)
    }

    @Test fun foreignCheckpointRootCannotExpandTheSelectedScope() {
        val file = record("/other/Uncertain/a.txt", "/other/Uncertain")
        val selected = InboxFilingIntake.select(listOf(file), setOf("/Download"), true, true, setOf("/other/Uncertain"))
        assertThat(selected.records).isEmpty()
    }

    @Test fun overlappingFolderScopesReviewAnIntactBundleExactlyOnce() {
        val root = "/Download"
        val bundle = record("$root/Lilith", root, directory = true)
        val notes = record("${bundle.stableRef}/Notes", bundle.stableRef, directory = true)
        val files = List(16_000) { record("${notes.stableRef}/$it.txt", notes.stableRef) }
        val selection = InboxFilingIntake.select(listOf(bundle, notes) + files + files.take(10),
            setOf(root, bundle.stableRef, notes.stableRef), false, true)
        assertThat(selection.records).containsExactly(bundle)
        assertThat(selection.indexedFolderDescendantCount).isEqualTo(16_001)
    }

    @Test fun opaqueProviderParentIdentityAlsoPreventsDoublePlanning() {
        val bundle = record("content://provider/document/42", "content://provider/document/1", "Lilith", true)
        val child = record("content://provider/document/99", bundle.stableRef)
        val selection = InboxFilingIntake.select(listOf(bundle, child), setOf(bundle.parentRef!!, bundle.stableRef), false, true)
        assertThat(selection.records).containsExactly(bundle)
        assertThat(selection.indexedFolderDescendantCount).isEqualTo(1)
    }

    private fun unresolved(source: String, parent: String) = FilingDecision(
        FilingArtifact(source, source.substringAfterLast('/'), "txt", 10, modifiedAt = 1, parentRef = parent),
        null, null, null, null, FilingConfidence.UNRESOLVED, emptyList(),
    )

    private fun record(ref: String, parent: String, name: String = ref.substringAfterLast('/'), directory: Boolean = false) = FileRecord(
        stableRef = ref, displayName = name, extension = if (directory) "" else "txt", mimeType = null,
        absolutePathOrUri = ref, parentRef = parent, sizeBytes = 10, createdAt = null, modifiedAt = 1,
        lastScannedAt = 1, isDirectory = directory, isHidden = false,
    )
}
