package com.pocketsteward.app.evidence

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.index.*
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.image.ImageInsight
import com.pocketsteward.app.image.ImageLabelScore
import com.pocketsteward.app.metadata.MetadataEnrichment
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FileEvidenceInspectorTest {
    @Test fun sharedReportKeepsTextOcrRulesPlacementAndModelAdviceDistinct() = runTest {
        val fixture = Fixture()
        val report = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef,
            review = EvidenceReviewContext("Lilith", destination = "/Documents/Lilith", reasons = listOf("Filename matches Lilith"), modelAdvice = "Possible manuscript")))
        assertThat(report.sections.map { it.origin }).containsAtLeast(EvidenceOrigin.FILE_METADATA, EvidenceOrigin.DOCUMENT_TEXT,
            EvidenceOrigin.DOCUMENT_OCR, EvidenceOrigin.USER_RULE, EvidenceOrigin.REVIEW_REASON, EvidenceOrigin.MODEL_ADVICE)
        assertThat(report.sections.single { it.origin == EvidenceOrigin.DOCUMENT_OCR }.title).contains("page 2")
        assertThat(report.sections.single { it.origin == EvidenceOrigin.MODEL_ADVICE }.lines).containsExactly("Possible manuscript")
    }
    @Test fun disabledInspectionReadsNoContentOrSamplesAndChangedReviewReadsNoDerivedEvidence() = runTest {
        val fixture = Fixture().apply { enabled = EvidencePrivacy(false, false, false) }
        val report = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef))
        assertThat(fixture.reads).isEqualTo(0)
        assertThat(report.sections.map { it.origin }).doesNotContain(EvidenceOrigin.DOCUMENT_TEXT)
        fixture.enabled = EvidencePrivacy(true, true, true)
        val changed = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef, expected = EvidenceExpectedSource(99, 1, false)))
        assertThat(changed.status).contains("Changed")
        assertThat(fixture.reads).isEqualTo(0)
    }
    @Test fun sameSizeByteChangeDuringInspectionDiscardsAllDerivedEvidence() = runTest {
        val fixture = Fixture().apply { afterDocument = { sample = "changed" } }
        val report = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef))
        assertThat(report.status).contains("changed during inspection")
        assertThat(report.sections.map { it.origin }).containsExactly(EvidenceOrigin.FILE_METADATA)
    }
    @Test fun revokedAndProtectedSourcesReadNoDerivedEvidence() = runTest {
        val fixture = Fixture().apply { allowed = false }
        assertThat(fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef)).status).contains("outside")
        assertThat(fixture.reads).isEqualTo(0)
        fixture.allowed = true; fixture.block = "Protected by no-sort marker"
        assertThat(fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef)).status).contains("Protected")
        assertThat(fixture.reads).isEqualTo(0)
    }
    @Test fun turningPrivacyOffMidInspectionRedactsMetadataAndStopsFurtherReads() = runTest {
        val fixture = Fixture().apply { afterMetadata = { enabled = EvidencePrivacy(false, false, false) } }
        val report = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef))
        assertThat(fixture.documentReads).isEqualTo(0)
        assertThat(report.sections.map { it.origin }).doesNotContain(EvidenceOrigin.EMBEDDED_METADATA)
    }
    @Test fun incompleteImageOcrDoesNotClaimNoTextAndLabelsAreNotGeneratedDescriptions() = runTest {
        val fixture = Fixture("png")
        val report = fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef))
        assertThat(report.sections.single { it.origin == EvidenceOrigin.IMAGE_OCR }.lines).containsExactly("OCR is unavailable or incomplete.")
        assertThat(report.sections.single { it.origin == EvidenceOrigin.IMAGE_LABEL }.lines.first()).contains("Landscape")
        assertThat(report.sections.map { it.origin }).doesNotContain(EvidenceOrigin.MODEL_ADVICE)
    }
    @Test(expected = CancellationException::class) fun cancellationIsNotReportedAsUnavailableEvidence() = runTest {
        val fixture = Fixture().apply { afterMetadata = { throw CancellationException() } }
        fixture.inspector().inspect(EvidenceRequest(fixture.record.stableRef))
    }
    private class Fixture(extension: String = "md") {
        val record = FileRecord(stableRef = "/D/Lilith.$extension", displayName = "Lilith.$extension", extension = extension, mimeType = null,
            absolutePathOrUri = "/D/Lilith.$extension", parentRef = "/D", sizeBytes = 12, createdAt = null, modifiedAt = 1,
            lastScannedAt = 1, isDirectory = false, isHidden = false)
        var enabled = EvidencePrivacy(true, true, true)
        var allowed = true
        var block: String? = null
        var sample = "same"
        var reads = 0
        var documentReads = 0
        var afterMetadata: () -> Unit = {}
        var afterDocument: () -> Unit = {}
        fun inspector() = FileEvidenceInspector(
            resolve = { record }, currentMode = { StorageAccessMode.DIRECT }, permitted = { _, _ -> allowed }, refusal = { _, _ -> block },
            observe = { _, _ -> FileMetadata(FileRef.Direct(record.stableRef), record.displayName, record.extension, null, 12, null, 1, false, false) },
            fingerprint = { _, _ -> reads++; sample }, privacy = { enabled },
            metadata = { reads++; afterMetadata(); MetadataEnrichment(record.copy(width = 100), false) },
            document = { _, _, _ -> reads++; documentReads++; afterDocument()
                DocumentEvidenceMaterial(IndexedDocument(record.stableRef, "/D", record.displayName, "/D", record.extension, "DOCUMENT", 12, 1, "same",
                    "TEXT", "INDEXED", null, 1, 1, 2, "FILING", false), listOf(IndexedSegment(stableRef = record.stableRef, ordinal = 0, pageNumber = 1, ocr = false, body = "Lilith notes"),
                    IndexedSegment(stableRef = record.stableRef, ordinal = 1, pageNumber = 2, ocr = true, body = "Scanned notes"))) },
            image = { _, _ -> reads++; ImageEvidenceMaterial(ImageInsight(record.stableRef, record.displayName, listOf(ImageLabelScore("Landscape", .9f)), false,
                textInspectionEnabled = true, textInspectionComplete = false), "OCR unavailable") },
            rules = { listOf(CorrectionRule("Lilith", "Lilith", "/D")) },
        )
    }
}
