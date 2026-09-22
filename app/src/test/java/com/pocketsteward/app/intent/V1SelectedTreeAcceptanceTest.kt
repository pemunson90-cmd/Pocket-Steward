package com.pocketsteward.app.intent

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanSelection
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class V1SelectedTreeAcceptanceTest {
    private val root = FileRef.Saf("content://provider/tree/root/document/root")

    @Test
    fun canonicalV1RequestPlansValidatesAndPersistsInsideGrantedTree() {
        val request =
            "Organize the obvious files by type. Put APKs together, put PDFs and documents together, " +
                "keep images separate, and leave anything uncertain alone."

        val parsed = DeterministicIntentParser.parse(request) as IntentParseResult.Parsed
        val records = buildList {
            repeat(200) { add(file("installer-$it.apk", "apk", 10_000L + it)) }
            repeat(200) { add(file("document-$it.pdf", "pdf", 20_000L + it)) }
            repeat(200) { add(file("photo-$it.jpg", "jpg", 30_000L + it)) }
            repeat(200) { add(file("mystery-$it.unknownblob", "unknownblob", 40_000L + it)) }
        }

        val generated = IntentPlanGenerator.generate(
            scopeRoot = root,
            records = records,
            projectKeywords = emptyList(),
            intent = parsed.intent,
        )

        val moves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves).hasSize(600)
        assertThat(moves.none { it.source.rawValue().contains("mystery-") }).isTrue()
        assertThat(moves.all { it.destination is FileRef.Child }).isTrue()

        val validated = PlanValidator.validate(
            generated.plan.operations,
            SelectedTreeIndex(root, records),
        )
        assertThat(validated.rejected).isEmpty()

        val selected = PlanSelection.safeSelected(validated.accepted)
        val approved = PlanSelection.selectedOperations(validated.accepted, selected)
        assertThat(approved).isNotEmpty()

        val durable = DurablePlanCodec.decodeOrNull(
            DurablePlanCodec.encode(request, approved),
        )
        assertThat(durable).isNotNull()
        assertThat(durable!!.operations).containsExactlyElementsIn(approved).inOrder()
        assertThat(durable.operations.filterIsInstance<PlannedOperation.Move>()
            .all { it.destination is FileRef.Child }).isTrue()
    }

    private fun file(name: String, extension: String, size: Long): FileRecord {
        val ref = FileRef.Saf("content://provider/tree/root/document/root%2F$name")
        return FileRecord(
            stableRef = ref.rawValue(),
            displayName = name,
            extension = extension,
            mimeType = null,
            absolutePathOrUri = ref.rawValue(),
            parentRef = root.rawValue(),
            sizeBytes = size,
            createdAt = null,
            modifiedAt = 1_700_000_000_000L,
            lastScannedAt = 1_700_000_000_000L,
            isDirectory = false,
            isHidden = false,
        )
    }

    private class SelectedTreeIndex(
        private val root: FileRef.Saf,
        records: List<FileRecord>,
    ) : FileIndex {
        private val byRef = records.associateBy { it.stableRef }

        override fun exists(ref: FileRef): Boolean = when (ref) {
            root -> true
            is FileRef.Child -> false
            else -> ref.rawValue() in byRef
        }

        override fun isDirectory(ref: FileRef): Boolean = ref == root

        override fun parentOf(ref: FileRef): FileRef? =
            byRef[ref.rawValue()]?.parentRef?.let(FileRef::Saf)

        override fun caseInsensitiveMatch(
            directory: FileRef,
            name: String,
            excluding: FileRef?,
        ): FileRef? =
            byRef.values
                .firstOrNull {
                    it.parentRef == directory.rawValue() &&
                        it.displayName.equals(name, ignoreCase = true) &&
                        it.stableRef != excluding?.rawValue()
                }
                ?.stableRef
                ?.let(FileRef::Saf)
    }
}
