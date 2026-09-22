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

class V1CoreAcceptanceTest {
    private val root = FileRef.Direct("/storage/emulated/0/Download")

    @Test
    fun canonicalV1RequestPlansValidatesDeselectsAndPersistsAtScale() {
        val request =
            "Organize the obvious files by type. Put APKs together, put PDFs and documents together, " +
                "keep images separate, and leave anything uncertain alone."

        val parsed = DeterministicIntentParser.parse(request) as IntentParseResult.Parsed
        assertThat(parsed.intent.action).isEqualTo(IntentAction.ORGANIZE)
        assertThat(parsed.intent.leaveUncertain).isTrue()

        val records = buildList {
            repeat(300) { add(file("installer-$it.apk", "apk", 10_000L + it)) }
            repeat(300) { add(file("document-$it.pdf", "pdf", 20_000L + it)) }
            repeat(300) { add(file("photo-$it.jpg", "jpg", 30_000L + it)) }
            repeat(300) { add(file("mystery-$it.blobthing", "blobthing", 40_000L + it)) }
        }

        val generated = IntentPlanGenerator.generate(
            scopeRoot = root,
            records = records,
            projectKeywords = emptyList(),
            intent = parsed.intent,
        )

        val moves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves).hasSize(900)
        assertThat(moves.none { it.source.rawValue().endsWith(".blobthing") }).isTrue()

        val index = AcceptanceIndex(root, records)
        val validated = PlanValidator.validate(generated.plan.operations, index)
        assertThat(validated.rejected).isEmpty()
        assertThat(validated.accepted).containsExactlyElementsIn(generated.plan.operations).inOrder()

        val firstMoveIndex = validated.accepted.indexOfFirst { it is PlannedOperation.Move }
        assertThat(firstMoveIndex).isAtLeast(0)

        val selected = PlanSelection.setSelected(
            operations = validated.accepted,
            current = PlanSelection.safeSelected(validated.accepted),
            index = firstMoveIndex,
            selected = false,
        )
        val approved = PlanSelection.selectedOperations(validated.accepted, selected)

        assertThat(approved).hasSize(validated.accepted.size - 1)

        val durable = DurablePlanCodec.decodeOrNull(
            DurablePlanCodec.encode(request, approved),
        )
        assertThat(durable).isNotNull()
        assertThat(durable!!.operations).containsExactlyElementsIn(approved).inOrder()
        assertThat(durable.operations.none {
            it is PlannedOperation.Move && it.source.rawValue().endsWith(".blobthing")
        }).isTrue()
    }

    private fun file(name: String, extension: String, size: Long): FileRecord =
        FileRecord(
            stableRef = "${root.absolutePath}/$name",
            displayName = name,
            extension = extension,
            mimeType = null,
            absolutePathOrUri = "${root.absolutePath}/$name",
            parentRef = root.absolutePath,
            sizeBytes = size,
            createdAt = null,
            modifiedAt = 1_700_000_000_000L,
            lastScannedAt = 1_700_000_000_000L,
            isDirectory = false,
            isHidden = false,
        )

    private class AcceptanceIndex(
        private val root: FileRef.Direct,
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
            byRef[ref.rawValue()]?.parentRef?.let(FileRef::Direct)

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
                ?.let(FileRef::Direct)
    }
}
