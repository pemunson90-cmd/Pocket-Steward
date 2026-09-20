package com.pocketsteward.app.content

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class ContentExtractorTest {
    @Test
    fun plainTextIsExtractedLocally() {
        val result = ContentExtractor.extract(
            "md",
            ByteArrayInputStream("hello Lilith world".toByteArray()),
        ) as ContentExtraction.Text

        assertThat(result.content).contains("Lilith")
        assertThat(result.kind).isEqualTo(ContentKind.PLAIN_TEXT)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun plainTextReadIsBounded() {
        val payload = ByteArray(ContentExtractor.MAX_TEXT_BYTES + 100) { 'a'.code.toByte() }

        val result = ContentExtractor.extract("txt", ByteArrayInputStream(payload)) as ContentExtraction.Text

        assertThat(result.content.length).isAtMost(ContentExtractor.MAX_TEXT_BYTES)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun docxDocumentXmlIsSearchableWithoutExternalLibrary() {
        val bytes = ooxml(
            "word/document.xml" to
                "<w:document><w:body><w:p><w:r><w:t>Lilith notes</w:t></w:r></w:p></w:body></w:document>",
        )

        val result = ContentExtractor.extract("docx", ByteArrayInputStream(bytes)) as ContentExtraction.Text

        assertThat(result.kind).isEqualTo(ContentKind.OOXML)
        assertThat(result.content).contains("Lilith notes")
    }

    @Test
    fun xlsxSharedStringsAreExtracted() {
        val bytes = ooxml(
            "xl/sharedStrings.xml" to
                "<sst><si><t>Leaseworld</t></si><si><t>Budget</t></si></sst>",
        )

        val result = ContentExtractor.extract("xlsx", ByteArrayInputStream(bytes)) as ContentExtraction.Text

        assertThat(result.content).contains("Leaseworld")
        assertThat(result.content).contains("Budget")
    }

    @Test
    fun pdfIsExplicitlyUnsupportedForNow() {
        val result = ContentExtractor.extract("pdf", ByteArrayInputStream(byteArrayOf()))

        assertThat(result).isInstanceOf(ContentExtraction.Unsupported::class.java)
    }

    private fun ooxml(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
