package com.pocketsteward.app.image

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import kotlinx.coroutines.*
import org.junit.Test

class ImageReviewBatchTest {
    private class Source : ImageEvidenceSource {
        val visual = mutableMapOf<String, ImageInsight>()
        val text = mutableMapOf<String, ImageInsight>()
        val attempts = mutableMapOf<Pair<String, Boolean>, Long>()
        val failures = mutableSetOf<Pair<String, Boolean>>()
        val calls = mutableListOf<Pair<String, Boolean>>()
        var broken = false
        var persistence = true
        var tick = 0L
        var beforeReturn: suspend () -> Unit = {}
        override suspend fun cached(record: FileRecord, inspectText: Boolean) = (if (inspectText) text else visual)[record.stableRef]
        override suspend fun attemptedAt(record: FileRecord, inspectText: Boolean) = attempts[record.stableRef to inspectText]
        override suspend fun unavailable(record: FileRecord, inspectText: Boolean) = record.stableRef to inspectText in failures
        override suspend fun markAttempt(record: FileRecord, inspectText: Boolean): Boolean {
            attempts[record.stableRef to false] = ++tick
            if (inspectText) attempts[record.stableRef to true] = tick
            return persistence
        }
        override suspend fun noteOutcome(record: FileRecord, inspectText: Boolean, insight: ImageInsight?): Boolean {
            if (insight == null) { failures += record.stableRef to false; if (inspectText) failures += record.stableRef to true }
            else { failures -= record.stableRef to false; if (inspectText) failures -= record.stableRef to true }
            return persistence
        }
        override suspend fun analyze(record: FileRecord, inspectText: Boolean, allowFresh: Boolean): ImageInsight? {
            check(allowFresh)
            calls += record.stableRef to inspectText
            beforeReturn()
            if (broken) return null
            val result = insight(record, inspectText)
            visual[record.stableRef] = insight(record)
            if (inspectText) text[record.stableRef] = result
            return result
        }
    }
    @Test fun sixteenThousandCandidatesRespectBothFreshBudgets() = runBlocking {
        val source = Source()
        val result = ImageReviewBatch(source).inspect(records(16_000), true)
        assertThat(source.calls).hasSize(200)
        assertThat(source.calls.count { it.second }).isEqualTo(40)
        assertThat(result.coverage.deferredImages).isEqualTo(15_800)
        assertThat(result.coverage.deferredOcr).isEqualTo(15_960)
        assertThat(result.evidence).hasSize(200)
    }
    @Test fun validCacheBeyondFreshBudgetIsStillUsed() = runBlocking {
        val source = Source()
        val records = records(1_000)
        records.takeLast(700).forEach { source.visual[it.stableRef] = insight(it) }
        val result = ImageReviewBatch(source).inspect(records, false)
        assertThat(source.calls).hasSize(200)
        assertThat(result.coverage.cached).isEqualTo(700)
        assertThat(result.evidence).hasSize(900)
        assertThat(result.coverage.deferredImages).isEqualTo(100)
    }
    @Test fun continuationEventuallyCompletesEveryVisualAndOcrWithoutReanalyzingFullCaches() = runBlocking {
        val source = Source()
        val records = records(1_000)
        var result = ImageReviewBatch(source).inspect(records, true)
        repeat(24) { result = ImageReviewBatch(source).inspect(records, true) }
        assertThat(result.coverage.hasDeferred).isFalse()
        assertThat(source.text).hasSize(1_000)
        assertThat(source.calls.count { it.second }).isEqualTo(1_000)
        val calls = source.calls.size
        result = ImageReviewBatch(source).inspect(records, true)
        assertThat(source.calls).hasSize(calls)
        assertThat(result.coverage.cached).isEqualTo(1_000)
    }
    @Test fun unavailableFailuresDoNotStarveNewFilesAndCanBeExplicitlyRetried() = runBlocking {
        val source = Source().apply { broken = true }
        val records = records(500)
        ImageReviewBatch(source).inspect(records, true)
        source.broken = false
        val result = ImageReviewBatch(source).inspect(records, true)
        assertThat(source.calls.map { it.first }.distinct()).hasSize(400)
        assertThat(result.coverage.unavailable).isEqualTo(200)
        repeat(14) { ImageReviewBatch(source).inspect(records, true, retryUnavailable = true) }
        assertThat(source.visual).hasSize(500)
        assertThat(source.text).hasSize(500)
    }
    @Test fun startedButInterruptedAttemptsGoBehindUnseenCandidates() = runBlocking {
        val source = Source()
        val records = records(500)
        records.take(200).forEach { source.markAttempt(it, false) }
        ImageReviewBatch(source).inspect(records, false)
        assertThat(source.calls.map { it.first }.toSet().intersect(records.take(200).map { it.stableRef }.toSet())).isEmpty()
    }
    @Test fun textDisabledDoesNotExposeSavedOcrOrRequestNewOcr() = runBlocking {
        val source = Source()
        val records = records(2)
        source.text[records[0].stableRef] = insight(records[0], true)
        source.visual[records[0].stableRef] = insight(records[0])
        val result = ImageReviewBatch(source).inspect(records, false)
        assertThat(source.calls.single().second).isFalse()
        assertThat(result.evidence.values.any { it.detectedText.isNotEmpty() }).isFalse()
        assertThat(result.coverage.textEnabled).isFalse()
    }
    @Test fun zeroBudgetStillUsesCachesAndCountsUniqueSupportedFiles() = runBlocking {
        val source = Source()
        val records = records(2)
        source.visual[records[0].stableRef] = insight(records[0])
        val result = ImageReviewBatch(source, ImageReviewBudget(0, 0)).inspect(records + records + records[0].copy(stableRef = "/dir", isDirectory = true), false)
        assertThat(result.coverage.total).isEqualTo(2)
        assertThat(result.coverage.cached).isEqualTo(1)
        assertThat(result.coverage.deferredImages).isEqualTo(1)
        assertThat(source.calls).isEmpty()
    }
    @Test fun persistenceWarningCountsEachAttemptOnce() = runBlocking {
        val source = Source().apply { persistence = false }
        val result = ImageReviewBatch(source).inspect(records(3), true)
        assertThat(result.coverage.progressNotSaved).isEqualTo(3)
        assertThat(result.coverage.summary).contains("could not be saved")
    }
    @Test fun cancellationFromUncooperativeAnalysisCannotPublishOrStartAnotherImage() = runBlocking {
        val source = Source()
        val parent = Job()
        source.beforeReturn = { parent.cancel() }
        val job = launch(parent) { ImageReviewBatch(source).inspect(records(500), true); error("Cancelled result escaped") }
        job.join()
        assertThat(source.calls).hasSize(1)
        assertThat(source.failures).isEmpty()
        assertThat(job.isCancelled).isTrue()
    }
    @Test fun cancellationAtLastProgressCallbackIsStillAcknowledged() = runBlocking {
        val parent = Job()
        val job = launch(parent) {
            ImageReviewBatch(Source(), ImageReviewBudget(0, 0)).inspect(records(1), false) { _, _, _, _ -> parent.cancel() }
            error("Cancelled result escaped")
        }
        job.join()
        assertThat(job.isCancelled).isTrue()
    }
    @Test(expected = IllegalArgumentException::class) fun negativeBudgetsAreRejected() { ImageReviewBudget(-1, 40) }
    companion object {
        fun records(count: Int) = List(count) { n ->
            val name = "%05d.png".format(n)
            FileRecord(stableRef = "/Downloads/Uncertain/$name", displayName = name, extension = "png", mimeType = "image/png",
                absolutePathOrUri = "/Downloads/Uncertain/$name", parentRef = "/Downloads/Uncertain", sizeBytes = 20,
                createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = false, isHidden = false)
        }
        fun insight(record: FileRecord, text: Boolean = false) = ImageInsight(record.stableRef, record.displayName,
            listOf(ImageLabelScore("Landscape", .8f)), false, 100, 200, detectedText = if (text) "Project: Lilith" else "",
            textInspectionEnabled = text, textInspectionComplete = text)
    }
}
