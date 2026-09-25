package com.pocketsteward.app.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

class PickerPathsTest {
    @Test
    fun idsRoundTrip() {
        for (path in listOf("", "Download/a.pdf", "Documents/Lease World/NSTL MS14.md", "DCIM/Camera/IMG_1.jpg")) {
            assertThat(PickerPaths.relativeFor(PickerPaths.idFor(path))).isEqualTo(path)
        }
    }

    @Test
    fun legacyIdsMapIntoManagedFolder() {
        assertThat(PickerPaths.relativeFor(PickerPaths.LEGACY_ROOT)).isEqualTo("PocketSteward")
        val legacy = "ps:" + Base64.getUrlEncoder().withoutPadding().encodeToString("Sorted/a.txt".toByteArray())
        assertThat(PickerPaths.relativeFor(legacy)).isEqualTo("PocketSteward/Sorted/a.txt")
    }

    @Test
    fun virtualAndUnknownIdsHaveNoPath() {
        assertThat(PickerPaths.relativeFor(PickerPaths.VIRTUAL_ROOT)).isNull()
        assertThat(PickerPaths.relativeFor(PickerPaths.VIRTUAL_RECENT)).isNull()
        assertThat(PickerPaths.relativeFor("garbage")).isNull()
        assertThat(PickerPaths.relativeFor("fs:!!!not-base64")).isNull()
    }

    @Test
    fun traversalIsRejected() {
        assertThat(PickerPaths.isPickable("../etc/passwd")).isFalse()
        assertThat(PickerPaths.isPickable("Download/../../x")).isFalse()
        val sneaky = "fs:" + Base64.getUrlEncoder().withoutPadding().encodeToString("../data".toByteArray())
        assertThat(PickerPaths.isPickable(PickerPaths.relativeFor(sneaky)!!)).isFalse()
    }

    @Test
    fun hiddenAndroidAndTrashAreNeverOffered() {
        assertThat(PickerPaths.isPickable(".thumbnails/x.jpg")).isFalse()
        assertThat(PickerPaths.isPickable("Download/.secret")).isFalse()
        assertThat(PickerPaths.isPickable("Android/data/com.x/files/a")).isFalse()
        assertThat(PickerPaths.isPickable("android/media/a")).isFalse()
        assertThat(PickerPaths.isPickable("PocketSteward/Trash/a.pdf")).isFalse()
        assertThat(PickerPaths.isPickable("PocketSteward/trash")).isFalse()
    }

    @Test
    fun ordinaryPathsAreOffered() {
        assertThat(PickerPaths.isPickable("")).isTrue()
        assertThat(PickerPaths.isPickable("Download/report.pdf")).isTrue()
        assertThat(PickerPaths.isPickable("PocketSteward/Sorted/a.txt")).isTrue()
        assertThat(PickerPaths.isPickable("Documents/Android notes.md")).isTrue()
    }

    @Test
    fun foldersFirstThenNewestFiles() {
        val items = listOf(
            PickerPaths.Entry("old.pdf", false, 100),
            PickerPaths.Entry("zeta", true, 5),
            PickerPaths.Entry("new.pdf", false, 900),
            PickerPaths.Entry("Alpha", true, 1),
            PickerPaths.Entry("mid.pdf", false, 500),
        )
        val sorted = PickerPaths.sortForPicking(items) { it }.map { it.name }
        assertThat(sorted).containsExactly("Alpha", "zeta", "new.pdf", "mid.pdf", "old.pdf").inOrder()
    }
}
