package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.cleanup.DO_NOT_SORT_MARKER
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

private const val DIR = "/sd/Pictures/Camera"

private fun entry(name: String, isDirectory: Boolean = false) = FileEntry(
    ref = FileRef.Direct("$DIR/$name"),
    displayName = name,
    isDirectory = isDirectory,
    parentRef = FileRef.Direct(DIR),
)

class SingleFolderIndexTest {

    @Test
    fun `the folder itself exists and is a directory`() {
        val index = SingleFolderIndex(FileRef.Direct(DIR), emptyList())

        assertThat(index.exists(FileRef.Direct(DIR))).isTrue()
        assertThat(index.isDirectory(FileRef.Direct(DIR))).isTrue()
    }

    @Test
    fun `a trailing slash on the folder does not change the answer`() {
        val index = SingleFolderIndex(FileRef.Direct("$DIR/"), emptyList())

        assertThat(index.exists(FileRef.Direct(DIR))).isTrue()
        assertThat(index.isDirectory(FileRef.Direct("$DIR/"))).isTrue()
    }

    @Test
    fun `it knows nothing about folders it was not given`() {
        val index = SingleFolderIndex(FileRef.Direct(DIR), listOf(entry("Sub", isDirectory = true)))

        assertThat(index.exists(FileRef.Direct("/sd/Pictures"))).isFalse()
        assertThat(index.exists(FileRef.Direct("$DIR/Sub/deeper.jpg"))).isFalse()
        assertThat(index.caseInsensitiveMatch(FileRef.Direct("/sd/Pictures"), "Camera")).isNull()
    }

    @Test
    fun `writing a marker into an unscanned folder validates`() {
        val index = SingleFolderIndex(FileRef.Direct(DIR), listOf(entry("IMG_001.jpg")))
        val op = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct(DIR),
            name = DO_NOT_SORT_MARKER,
            content = "x",
            reason = "protect",
        )

        assertThat(PlanValidator.validate(listOf(op), index).accepted).containsExactly(op)
    }

    @Test
    fun `a marker already there blocks a second write and allows the trash that removes it`() {
        val index = SingleFolderIndex(FileRef.Direct(DIR), listOf(entry(DO_NOT_SORT_MARKER)))
        val write = PlannedOperation.WriteTextFile(
            parent = FileRef.Direct(DIR),
            name = DO_NOT_SORT_MARKER,
            content = "x",
            reason = "protect",
        )
        val trash = PlannedOperation.Trash(
            source = FileRef.Direct("$DIR/$DO_NOT_SORT_MARKER"),
            reason = "unprotect",
        )

        assertThat(PlanValidator.validate(listOf(write), index).accepted).isEmpty()
        assertThat(PlanValidator.validate(listOf(trash), index).accepted).containsExactly(trash)
    }

    @Test
    fun `case-insensitive collision is caught inside the folder`() {
        val index = SingleFolderIndex(FileRef.Direct(DIR), listOf(entry("Report.TXT")))

        assertThat(index.caseInsensitiveMatch(FileRef.Direct(DIR), "report.txt"))
            .isEqualTo(FileRef.Direct("$DIR/Report.TXT"))
    }
}

class CustomFolderTargetTest {

    @Test
    fun `the label is the folder's own name`() {
        assertThat(com.pocketsteward.app.ui.scan.ScanTarget.CustomFolder("/sd/Pictures/Camera").label)
            .isEqualTo("Camera")
    }

    @Test
    fun `a trailing slash does not produce a blank label`() {
        assertThat(com.pocketsteward.app.ui.scan.ScanTarget.CustomFolder("/sd/Pictures/Camera/").label)
            .isEqualTo("Camera")
    }

    @Test
    fun `a path with no separator falls back to the whole path`() {
        assertThat(com.pocketsteward.app.ui.scan.ScanTarget.CustomFolder("/").label).isEqualTo("/")
    }
}
