package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun trash(
    sequence: Int,
    from: String,
    to: String?,
    sha: String?,
    keeper: String?,
    succeeded: Boolean = true,
    error: String? = null,
) = ManifestEntry(
    sequence = sequence,
    operation = "TRASH",
    originalPath = from,
    resultPath = to,
    fingerprint = sha,
    succeeded = succeeded,
    error = error,
    reason = keeper?.let(::duplicateTrashReason),
)

class KeeperReasonTest {

    @Test
    fun `the reason round trips a full path`() {
        val path = "/sd/Download/SomeProject/cover (final).jpg"

        assertThat(keeperPathFrom(duplicateTrashReason(path))).isEqualTo(path)
    }

    @Test
    fun `a reason written by something else yields no keeper`() {
        assertThat(keeperPathFrom("APK file")).isNull()
        assertThat(keeperPathFrom(null)).isNull()
        assertThat(keeperPathFrom("Duplicate of something")).isNull()
    }

    @Test
    fun `a blank path is not treated as a keeper`() {
        assertThat(keeperPathFrom(duplicateTrashReason(""))).isNull()
    }
}

class DuplicateAssertionTest {

    @Test
    fun `two groups keeping one copy each is consistent`() {
        val entries = listOf(
            trash(0, "/sd/a/1.jpg", "/sd/Trash/a/1.jpg", "aaa", "/sd/keep/1.jpg"),
            trash(1, "/sd/b/1.jpg", "/sd/Trash/b/1.jpg", "aaa", "/sd/keep/1.jpg"),
            trash(2, "/sd/c/2.pdf", "/sd/Trash/c/2.pdf", "bbb", "/sd/keep/2.pdf"),
        )

        val assertion = TaskManifest.duplicateAssertion(entries)

        assertThat(assertion.groups).isEqualTo(2)
        assertThat(assertion.kept).isEqualTo(2)
        assertThat(assertion.trashed).isEqualTo(3)
        assertThat(assertion.consistent).isTrue()
        assertThat(assertion.headline()).isEqualTo("2 duplicate groups, 2 copies kept, 3 copies trashed.")
    }

    @Test
    fun `a group that lost every copy is called out loudly`() {
        // One fingerprint, but no reason naming a survivor: kept is 0.
        val entries = listOf(
            trash(0, "/sd/a/1.jpg", "/sd/Trash/a/1.jpg", "aaa", keeper = null),
            trash(1, "/sd/b/1.jpg", "/sd/Trash/b/1.jpg", "aaa", keeper = null),
        )

        val assertion = TaskManifest.duplicateAssertion(entries)

        assertThat(assertion.consistent).isFalse()
        assertThat(assertion.headline()).startsWith("CHECK THIS:")
    }

    @Test
    fun `a failed trash is not counted as trashed`() {
        val entries = listOf(
            trash(0, "/sd/a/1.jpg", "/sd/Trash/a/1.jpg", "aaa", "/sd/keep/1.jpg"),
            trash(1, "/sd/b/1.jpg", null, "aaa", "/sd/keep/1.jpg", succeeded = false, error = "permission denied"),
        )

        val assertion = TaskManifest.duplicateAssertion(entries)

        assertThat(assertion.trashed).isEqualTo(1)
        assertThat(assertion.consistent).isTrue()
    }

    @Test
    fun `singular counts read as singular`() {
        val entries = listOf(trash(0, "/sd/a/1.jpg", "/sd/Trash/a/1.jpg", "aaa", "/sd/keep/1.jpg"))

        assertThat(TaskManifest.duplicateAssertion(entries).headline())
            .isEqualTo("1 duplicate group, 1 copy kept, 1 copy trashed.")
    }
}

class ManifestRenderTest {

    @Test
    fun `every trashed file names its origin its new home and its twin`() {
        val entries = listOf(
            trash(0, "/sd/a/1.jpg", "/sd/PocketSteward/Trash/a/1.jpg", "aaa", "/sd/keep/1.jpg"),
        )

        val text = TaskManifest.render("Trash duplicates", "yesterday", "Completed", entries)

        assertThat(text).contains("Kept: /sd/keep/1.jpg")
        assertThat(text).contains("SHA-256: aaa")
        assertThat(text).contains("was: /sd/a/1.jpg")
        assertThat(text).contains("now: /sd/PocketSteward/Trash/a/1.jpg")
        assertThat(text).contains("1 duplicate group, 1 copy kept, 1 copy trashed.")
    }

    @Test
    fun `failures are rendered with their error text`() {
        val entries = listOf(
            ManifestEntry(0, "MOVE", "/sd/a.pdf", null, null, succeeded = false, error = "Destination already exists", reason = "PDF file"),
        )

        val text = TaskManifest.render("Smart cleanup", "yesterday", "Partial", entries)

        assertThat(text).contains("Failed (1)")
        assertThat(text).contains("/sd/a.pdf")
        assertThat(text).contains("Destination already exists")
    }

    @Test
    fun `the no-delete guarantee is stated in every manifest`() {
        val text = TaskManifest.render("Anything", "now", "Completed", emptyList())

        assertThat(text).contains("Nothing in this run was deleted.")
    }
}

class PlanJsonReasonsTest {

    @Test
    fun `reasons are recovered by sequence and the goal line is ignored`() {
        val planJson = buildString {
            appendLine("Smart cleanup")
            appendLine("0\tCREATE_DIRECTORY\tDestination folder for: PDF file")
            appendLine("1\tMOVE\tPDF file")
            appendLine("# left untouched")
            appendLine("-\tMOVE\tDestination already exists")
        }

        val reasons = TaskManifest.reasonsBySequence(planJson)

        assertThat(reasons).containsExactly(
            0, "Destination folder for: PDF file",
            1, "PDF file",
        )
    }

    @Test
    fun `a reason containing a tab survives the round trip`() {
        val reasons = TaskManifest.reasonsBySequence("goal\n7\tTRASH\ta\tb")

        assertThat(reasons[7]).isEqualTo("a\tb")
    }
}
