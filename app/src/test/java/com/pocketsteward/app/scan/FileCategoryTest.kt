package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileCategoryTest {

    @Test
    fun `apk always wins regardless of case`() {
        assertThat(classifyByExtension("apk")).isEqualTo(FileCategory.APK)
        assertThat(classifyByExtension("APK")).isEqualTo(FileCategory.APK)
    }

    @Test
    fun `known image, document, archive, and audio-video extensions classify correctly`() {
        assertThat(classifyByExtension("jpg")).isEqualTo(FileCategory.IMAGE)
        assertThat(classifyByExtension("pdf")).isEqualTo(FileCategory.DOCUMENT)
        assertThat(classifyByExtension("zip")).isEqualTo(FileCategory.ARCHIVE)
        assertThat(classifyByExtension("mp3")).isEqualTo(FileCategory.AUDIO_VIDEO)
    }

    @Test
    fun `unknown or empty extension falls back to other`() {
        assertThat(classifyByExtension("xyz123")).isEqualTo(FileCategory.OTHER)
        assertThat(classifyByExtension("")).isEqualTo(FileCategory.OTHER)
    }
}
