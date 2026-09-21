package com.pocketsteward.app.semantic

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class SemanticPlanAdapterTest {
    private val downloads = FileRef.Direct("/storage/emulated/0/Download")
    private val documents = FileRef.Direct("/storage/emulated/0/Documents")

    @Test
    fun suggestedGroupsStayInsideOriginatingRoot() {
        val a = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)
        val b = record("/storage/emulated/0/Documents/b.txt", "b.txt", documents.absolutePath)

        val result = SemanticPlanAdapter.build(
            scopeRoots = listOf(downloads, documents),
            records = listOf(a, b),
            suggestions = listOf(
                suggestion(a, "Notes"),
                suggestion(b, "Notes"),
            ),
        )

        val moves = result.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves.map { (it.destination as FileRef.Direct).absolutePath }).containsExactly(
            "/storage/emulated/0/Download/Notes/a.txt",
            "/storage/emulated/0/Documents/Notes/b.txt",
        )
    }

    @Test
    fun recommendedDocumentsPolicyMovesDownloadsDocumentsCrossRoot() {
        val file = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)

        val result = SemanticPlanAdapter.build(
            scopeRoots = listOf(downloads),
            records = listOf(file),
            suggestions = listOf(suggestion(file, "Project Notes")),
            destinationChoice = SemanticDestinationChoice(DestinationPolicy.RECOMMENDED_DOCUMENTS),
            recommendedDocumentsRoot = documents,
        )

        val move = result.operations.filterIsInstance<PlannedOperation.Move>().single()
        assertThat((move.destination as FileRef.Direct).absolutePath)
            .isEqualTo("/storage/emulated/0/Documents/Project Notes/a.txt")
        assertThat(result.authorizedDestinationRoots).containsExactly(documents)
    }

    @Test
    fun explicitPolicyUsesOnlyTheUserApprovedRoot() {
        val file = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)
        val explicit = FileRef.Direct("/storage/emulated/0/MyWriting")

        val result = SemanticPlanAdapter.build(
            scopeRoots = listOf(downloads),
            records = listOf(file),
            suggestions = listOf(suggestion(file, "Drafts")),
            destinationChoice = SemanticDestinationChoice(
                policy = DestinationPolicy.EXPLICIT_FOLDER,
                explicitRoot = explicit,
            ),
            recommendedDocumentsRoot = documents,
        )

        val create = result.operations.filterIsInstance<PlannedOperation.CreateDirectory>().single()
        assertThat(create.parent).isEqualTo(explicit)
        assertThat(result.authorizedDestinationRoots).containsExactly(explicit)
    }

    @Test
    fun traversalLikeGroupIsRejectedRatherThanInterpretedAsPath() {
        val file = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)

        val result = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(file),
            listOf(suggestion(file, "../Documents")),
        )

        assertThat(result.operations).isEmpty()
        assertThat(result.skipped.single().reason).contains("unsafe")
    }

    @Test
    fun protectedSubtreeAlwaysWins() {
        val marker = record(
            "/storage/emulated/0/Download/Project/$DO_NOT_SORT_MARKER",
            DO_NOT_SORT_MARKER,
            "/storage/emulated/0/Download/Project",
        )
        val file = record(
            "/storage/emulated/0/Download/Project/a.txt",
            "a.txt",
            "/storage/emulated/0/Download/Project",
        )

        val result = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(marker, file),
            listOf(suggestion(file, "Notes")),
            includeSubfolders = true,
        )

        assertThat(result.operations).isEmpty()
        assertThat(result.skipped.single().reason).contains("protected")
    }

    @Test
    fun nestedFilesAreSkippedByDefaultAndAllowedOnlyWhenExplicit() {
        val file = record(
            "/storage/emulated/0/Download/Old/a.txt",
            "a.txt",
            "/storage/emulated/0/Download/Old",
        )
        val suggestion = suggestion(file, "Notes")

        val defaultResult = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(file),
            listOf(suggestion),
        )
        val enabledResult = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(file),
            listOf(suggestion),
            includeSubfolders = true,
        )

        assertThat(defaultResult.operations).isEmpty()
        assertThat(enabledResult.operations.filterIsInstance<PlannedOperation.Move>()).hasSize(1)
    }

    @Test
    fun belongsAndUncertainNeverBecomeMoves() {
        val a = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)
        val b = record("/storage/emulated/0/Download/b.txt", "b.txt", downloads.absolutePath)

        val result = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(a, b),
            listOf(
                SemanticSuggestion(a.stableRef, CoherenceClass.BELONGS, "Notes"),
                SemanticSuggestion(b.stableRef, CoherenceClass.UNCERTAIN, "Notes"),
            ),
        )

        assertThat(result.operations).isEmpty()
    }

    @Test
    fun duplicateFindingsForOneFileDoNotDoubleMove() {
        val file = record("/storage/emulated/0/Download/a.txt", "a.txt", downloads.absolutePath)
        val finding = suggestion(file, "Notes")

        val result = SemanticPlanAdapter.build(
            listOf(downloads),
            listOf(file),
            listOf(finding, finding),
        )

        assertThat(result.operations).isEmpty()
        assertThat(result.skipped.single().reason).contains("duplicate")
    }

    @Test
    fun safeUnicodeGroupNamesArePreserved() {
        assertThat(SemanticPlanAdapter.sanitizeGroup("  Café Notes 📚  ")).isEqualTo("Café Notes 📚")
    }

    private fun suggestion(record: FileRecord, group: String) =
        SemanticSuggestion(record.stableRef, CoherenceClass.DOES_NOT_BELONG, group)

    private fun record(path: String, name: String, parent: String) = FileRecord(
        stableRef = path,
        displayName = name,
        extension = name.substringAfterLast('.', ""),
        mimeType = null,
        absolutePathOrUri = path,
        parentRef = parent,
        sizeBytes = 1,
        createdAt = null,
        modifiedAt = null,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
