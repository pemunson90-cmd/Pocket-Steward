package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

/**
 * V1 acceptance-edge matrix from the foundational spec. These tests are
 * deliberately synthetic: each isolates a filesystem state change that must
 * fail closed before the executor sees it.
 */
class V1FailureMatrixTest {
    private class Index(
        existing: Set<String>,
        directories: Set<String>,
    ) : FileIndex {
        private val existing = existing.toMutableSet().apply { addAll(directories) }
        private val directories = directories.toSet()

        override fun exists(ref: FileRef): Boolean =
            (ref as? FileRef.Direct)?.absolutePath in existing

        override fun isDirectory(ref: FileRef): Boolean =
            (ref as? FileRef.Direct)?.absolutePath in directories

        override fun caseInsensitiveMatch(
            directory: FileRef,
            name: String,
            excluding: FileRef?,
        ): FileRef? {
            val parent = (directory as? FileRef.Direct)?.absolutePath?.trimEnd('/') ?: return null
            return existing.asSequence()
                .filter { it.substringBeforeLast('/', "") == parent }
                .map(FileRef::Direct)
                .filterNot { it == excluding }
                .firstOrNull { it.absolutePath.substringAfterLast('/').equals(name, ignoreCase = true) }
        }
    }

    private fun ref(path: String) = FileRef.Direct(path)

    @Test
    fun missingSourceAfterPreviewIsRejected() {
        val index = Index(
            existing = emptySet(),
            directories = setOf("/storage/emulated/0/Download", "/storage/emulated/0/Documents"),
        )
        val op = PlannedOperation.Move(
            ref("/storage/emulated/0/Download/report.pdf"),
            ref("/storage/emulated/0/Documents/report.pdf"),
            "planned before source disappeared",
        )

        val validated = PlanValidator.validate(listOf(op), index)

        assertThat(validated.accepted).isEmpty()
        assertThat(validated.rejected.single().reason).contains("Source does not exist")
    }

    @Test
    fun destinationCreatedConcurrentlyIsRejected() {
        val index = Index(
            existing = setOf(
                "/storage/emulated/0/Download/report.pdf",
                "/storage/emulated/0/Documents/report.pdf",
            ),
            directories = setOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Documents",
            ),
        )
        val op = PlannedOperation.Move(
            ref("/storage/emulated/0/Download/report.pdf"),
            ref("/storage/emulated/0/Documents/report.pdf"),
            "destination appeared after preview",
        )

        assertThat(PlanValidator.validate(listOf(op), index).accepted).isEmpty()
    }

    @Test
    fun caseInsensitiveCollisionIsRejected() {
        val index = Index(
            existing = setOf(
                "/storage/emulated/0/Download/report.txt",
                "/storage/emulated/0/Download/REPORT.TXT",
            ),
            directories = setOf("/storage/emulated/0/Download"),
        )

        val result = PlanValidator.validate(
            listOf(
                PlannedOperation.Rename(
                    ref("/storage/emulated/0/Download/report.txt"),
                    "Report.txt",
                    "case collision",
                ),
            ),
            index,
        )

        assertThat(result.accepted).isEmpty()
    }

    @Test
    fun unicodeAndEmojiNamesRemainLegalPathSegments() {
        val index = Index(
            existing = setOf("/storage/emulated/0/Download/原稿-🦇.md"),
            directories = setOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Documents",
            ),
        )
        val op = PlannedOperation.Move(
            ref("/storage/emulated/0/Download/原稿-🦇.md"),
            ref("/storage/emulated/0/Documents/原稿-🦇.md"),
            "unicode path",
        )

        assertThat(PlanValidator.validate(listOf(op), index).accepted).containsExactly(op)
    }

    @Test
    fun directoryCannotBeMovedUnderItselfEvenWithDeepDestination() {
        val index = Index(
            existing = emptySet(),
            directories = setOf(
                "/storage/emulated/0/Download/Project",
                "/storage/emulated/0/Download/Project/Sub",
            ),
        )
        val op = PlannedOperation.Move(
            ref("/storage/emulated/0/Download/Project"),
            ref("/storage/emulated/0/Download/Project/Sub/Nested/Project"),
            "recursive",
        )

        assertThat(PlanValidator.validate(listOf(op), index).accepted).isEmpty()
    }

    @Test
    fun pathTraversalNeverBecomesAPlanOperation() {
        val index = Index(
            existing = setOf("/storage/emulated/0/Download/a.txt"),
            directories = setOf("/storage/emulated/0/Download"),
        )
        val op = PlannedOperation.Rename(
            ref("/storage/emulated/0/Download/a.txt"),
            "../escape.txt",
            "bad",
        )

        val result = PlanValidator.validate(listOf(op), index)

        assertThat(result.accepted).isEmpty()
        assertThat(result.rejected.single().reason).contains("traversal")
    }

    @Test
    fun twoOperationsCannotClaimSameDestination() {
        val index = Index(
            existing = setOf(
                "/storage/emulated/0/Download/a.txt",
                "/storage/emulated/0/Download/b.txt",
            ),
            directories = setOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Documents",
            ),
        )
        val destination = ref("/storage/emulated/0/Documents/result.txt")
        val result = PlanValidator.validate(
            listOf(
                PlannedOperation.Move(ref("/storage/emulated/0/Download/a.txt"), destination, "a"),
                PlannedOperation.Move(ref("/storage/emulated/0/Download/b.txt"), destination, "b"),
            ),
            index,
        )

        assertThat(result.accepted).hasSize(1)
        assertThat(result.rejected).hasSize(1)
    }

    @Test
    fun reviewedPlanPackageRejectsTamperedPayload() {
        val packageJson = ReviewedPlanPackage.encode(
            "test",
            listOf(
                PlannedOperation.Rename(
                    ref("/storage/emulated/0/Download/a.txt"),
                    "b.txt",
                    "rename",
                ),
            ),
        )
        val tampered = packageJson.replace(
            Regex("""\"durablePlanBase64\"\s*:\s*\"[^\"]+\""""),
            "\"durablePlanBase64\": \"not-valid-base64***\"",
        )

        assertThat(ReviewedPlanPackage.decodeOrNull(tampered)).isNull()
    }
}
