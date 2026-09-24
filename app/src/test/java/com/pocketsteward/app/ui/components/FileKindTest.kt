package com.pocketsteward.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileKindTest {
    @Test
    fun extensionDecidesKindCaseInsensitively() {
        assertThat(FileKind.of("IMG_2031.HEIC")).isEqualTo(FileKind.IMAGE)
        assertThat(FileKind.of("clip.mp4")).isEqualTo(FileKind.VIDEO)
        assertThat(FileKind.of("Invoice.PDF")).isEqualTo(FileKind.PDF)
        assertThat(FileKind.of("budget.xlsx")).isEqualTo(FileKind.SPREADSHEET)
        assertThat(FileKind.of("app-release.apk")).isEqualTo(FileKind.APP)
        assertThat(FileKind.of("notes.md")).isEqualTo(FileKind.TEXT)
    }

    @Test
    fun extensionBeatsAGenericMimeType() {
        assertThat(FileKind.of("photo.jpg", "application/octet-stream")).isEqualTo(FileKind.IMAGE)
    }

    @Test
    fun mimeTypeFillsInWhenTheNameHasNoKnownExtension() {
        assertThat(FileKind.of("download", "image/png")).isEqualTo(FileKind.IMAGE)
        assertThat(FileKind.of("download", "application/pdf")).isEqualTo(FileKind.PDF)
        assertThat(FileKind.of("download", null)).isEqualTo(FileKind.OTHER)
    }

    @Test
    fun directoriesAreFoldersWhateverTheirName() {
        assertThat(FileKind.of("photos.jpg", isDirectory = true)).isEqualTo(FileKind.FOLDER)
    }

    @Test
    fun dotfilesAndTrailingDotsHaveNoExtension() {
        assertThat(FileKind.extensionOf(".nomedia")).isEmpty()
        assertThat(FileKind.extensionOf("weird.")).isEmpty()
        assertThat(FileKind.extensionOf("a.tar.gz")).isEqualTo("gz")
    }

    @Test
    fun badgeShowsShortExtensionsAndFallsBackForLongOnes() {
        assertThat(FileKind.badgeText("a.xlsx", FileKind.SPREADSHEET)).isEqualTo("XLSX")
        assertThat(FileKind.badgeText("a.markdown", FileKind.TEXT)).isEqualTo("MAR")
        assertThat(FileKind.badgeText("README", FileKind.OTHER)).isEqualTo("FILE")
        assertThat(FileKind.badgeText("Camera", FileKind.FOLDER)).isEqualTo("DIR")
    }

    @Test
    fun onlyImagesVideosAndPdfsAskForARealPreview() {
        val previewable = FileKind.entries.filter { it.canPreview }.toSet()
        assertThat(previewable).containsExactly(FileKind.IMAGE, FileKind.VIDEO, FileKind.PDF)
    }
}
