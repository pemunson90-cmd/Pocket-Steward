package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Test

class ContentIndexPolicyTest {
    @Test
    fun unchangedMetadataAtCurrentExtractorVersionIsReusable() {
        val record = record(size = 10, modified = 20, extension = "pdf")
        val existing = IndexedDocument(
            stableRef = record.stableRef,
            sourceRoot = "/root",
            displayName = record.displayName,
            parentRef = record.parentRef,
            extension = "pdf",
            category = "DOCUMENT",
            sizeBytes = 10,
            modifiedAt = 20,
            quickFingerprint = null,
            contentKind = "PDF_TEXT",
            extractionStatus = "INDEXED",
            extractionError = null,
            extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
            indexedAt = 1,
            segmentCount = 1,
        )

        assertThat(ContentIndexPolicy.canReuse(existing, record)).isTrue()
    }

    @Test
    fun changedMetadataForcesReextract() {
        val existing = IndexedDocument(
            stableRef = "/root/a.pdf",
            sourceRoot = "/root",
            displayName = "a.pdf",
            parentRef = "/root",
            extension = "pdf",
            category = "DOCUMENT",
            sizeBytes = 10,
            modifiedAt = 20,
            quickFingerprint = null,
            contentKind = "PDF_TEXT",
            extractionStatus = "INDEXED",
            extractionError = null,
            extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION,
            indexedAt = 1,
            segmentCount = 1,
        )

        assertThat(ContentIndexPolicy.canReuse(existing, record(size = 11, modified = 20, extension = "pdf"))).isFalse()
        assertThat(ContentIndexPolicy.canReuse(existing, record(size = 10, modified = 21, extension = "pdf"))).isFalse()
        assertThat(ContentIndexPolicy.canReuse(existing, record(size = 10, modified = 20, extension = "docx"))).isFalse()
    }

    @Test
    fun extractorVersionChangeForcesReextract() {
        val record = record(size = 10, modified = 20, extension = "pdf")
        val existing = IndexedDocument(
            stableRef = record.stableRef,
            sourceRoot = "/root",
            displayName = record.displayName,
            parentRef = record.parentRef,
            extension = "pdf",
            category = "DOCUMENT",
            sizeBytes = 10,
            modifiedAt = 20,
            quickFingerprint = null,
            contentKind = "PDF_TEXT",
            extractionStatus = "INDEXED",
            extractionError = null,
            extractorVersion = ContentIndexPolicy.EXTRACTOR_VERSION - 1,
            indexedAt = 1,
            segmentCount = 1,
        )

        assertThat(ContentIndexPolicy.canReuse(existing, record)).isFalse()
    }

    @Test
    fun ftsQueryQuotesTokensAndNeutralizesOperators() {
        assertThat(ContentFtsQuery.build("pain OR arousal"))
            .isEqualTo(""pain" AND "OR" AND "arousal"")
    }

    @Test
    fun ftsQueryEscapesEmbeddedQuotes() {
        assertThat(ContentFtsQuery.build("the "quoted" thing"))
            .isEqualTo(""the" AND """quoted""" AND "thing"")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankFtsQueryIsRejected() {
        ContentFtsQuery.build("   ")
    }

    private fun record(size: Long, modified: Long?, extension: String) = FileRecord(
        stableRef = "/root/a.$extension",
        displayName = "a.$extension",
        extension = extension,
        mimeType = null,
        absolutePathOrUri = "/root/a.$extension",
        parentRef = "/root",
        sizeBytes = size,
        createdAt = null,
        modifiedAt = modified,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
