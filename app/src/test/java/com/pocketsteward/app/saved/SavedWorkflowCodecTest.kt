package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedWorkflowCodecTest {
    @Test
    fun roundTripsNamesRequestsAndUnicodePaths() {
        val input = listOf(
            SavedWorkflow(
                id = "abc",
                name = "Writing 📚",
                request = "find documents containing Lilith",
                roots = listOf("/storage/emulated/0/Documents", "/storage/emulated/0/Books/Café"),
            ),
        )

        assertThat(SavedWorkflowCodec.decode(SavedWorkflowCodec.encode(input))).isEqualTo(input)
    }

    @Test
    fun malformedLinesAreIgnoredWithoutDestroyingValidEntries() {
        val valid = SavedWorkflow("1", "Downloads", "", listOf("/storage/emulated/0/Download"))
        val raw = "garbage\n" + SavedWorkflowCodec.encode(listOf(valid))

        assertThat(SavedWorkflowCodec.decode(raw)).containsExactly(valid)
    }

    @Test
    fun legacyRecipesKeepRequestBehaviorAndTypedRecipesRoundTrip() {
        val request = SavedWorkflow("1", "Old", "find notes", listOf("/a"))
        val legacy = SavedWorkflowCodec.encode(listOf(request)).substringBeforeLast(';')
        assertThat(SavedWorkflowCodec.decode(legacy)).containsExactly(request)
        val inbox = request.copy(kind = WorkflowKind.INBOX_FILING)
        val uncertain = request.copy(id = "2", kind = WorkflowKind.UNCERTAIN_FILING)
        assertThat(SavedWorkflowCodec.decode(SavedWorkflowCodec.encode(listOf(inbox, uncertain)))).containsExactly(inbox, uncertain)
        assertThat(SavedWorkflowCodec.decode(legacy + ";UNSUPPORTED")).isEmpty()
    }

    @Test
    fun duplicateRootsCollapseDuringDecode() {
        val value = SavedWorkflow("1", "x", "", listOf("/a", "/a"))

        val decoded = SavedWorkflowCodec.decode(SavedWorkflowCodec.encode(listOf(value))).single()

        assertThat(decoded.roots).containsExactly("/a")
    }
}
