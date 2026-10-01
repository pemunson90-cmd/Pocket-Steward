package com.pocketsteward.app.content

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContentInspectionBudgetTest {
    @Test fun filingTextIsBoundedAndExplicitlyPartial() {
        val text = "Lilith notes " + "x".repeat(50_000)
        val extracted = ContentExtractor.extract("txt", ByteArrayInputStream(text.toByteArray()), ContentInspectionBudget.FILING) as ContentExtraction.Text
        assertThat(extracted.content.length).isAtMost(12_000)
        assertThat(extracted.truncated).isTrue()
        assertThat(extracted.content).startsWith("Lilith")
    }
    @Test fun nonTextOfficeEntryCannotForceUnlimitedDecompression() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/media/huge.bin")); repeat(10_000) { zip.write(ByteArray(1024)) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write("<w:t>Lilith</w:t>".toByteArray()); zip.closeEntry()
        }
        val extracted = ContentExtractor.extract("docx", ByteArrayInputStream(bytes.toByteArray()), ContentInspectionBudget.FILING) as ContentExtraction.Text
        assertThat(extracted.truncated).isTrue()
        assertThat(extracted.content).doesNotContain("Lilith")
    }
    @Test fun inspectionCopyStopsDespiteFalseProviderLength() = runTest {
        val output = ByteArrayOutputStream()
        val failure = runCatching { copyInspectionSource(ByteArrayInputStream(ByteArray(200)), output, limit = 100) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(output.size()).isAtMost(100)
    }
    @Test fun exactLimitIsAllowedAndCancellationIsNotAnExtractionFailure() = runTest {
        val output = ByteArrayOutputStream()
        copyInspectionSource(ByteArrayInputStream(ByteArray(100)), output, limit = 100)
        assertThat(output.size()).isEqualTo(100)
        val cancelled = launch { cancel(); copyInspectionSource(ByteArrayInputStream(ByteArray(100)), output, 100) }
        cancelled.join()
        assertThat(output.size()).isEqualTo(100)
        val throwing = object : InputStream() { override fun read(): Int = throw CancellationException("Stopped") }
        assertThat(runCatching { ContentExtractor.extract("txt", throwing) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}
