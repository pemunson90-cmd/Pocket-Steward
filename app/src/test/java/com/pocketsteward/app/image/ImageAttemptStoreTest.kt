package com.pocketsteward.app.image

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageAttemptStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun progressSurvivesRestartAndSeparatesVisualFromText() {
        val dir = temporary.newFolder()
        val record = ImageReviewBatchTest.records(1).single()
        val store = ImageAttemptStore(dir) { 123 }
        assertThat(store.write(record, false, ImageAttemptOutcome.UNAVAILABLE)).isTrue()
        val restarted = ImageAttemptStore(dir)
        assertThat(restarted.read(record, false)).isEqualTo(ImageAttempt(123, ImageAttemptOutcome.UNAVAILABLE))
        assertThat(restarted.read(record, true)).isNull()
        assertThat(store.write(record, true)).isTrue()
        assertThat(restarted.read(record, true)?.outcome).isEqualTo(ImageAttemptOutcome.STARTED)
    }
    @Test fun changedSourceInvalidatesTheSchedulingHint() {
        val store = ImageAttemptStore(temporary.newFolder())
        val record = ImageReviewBatchTest.records(1).single()
        store.write(record, false)
        for (changed in listOf(record.copy(displayName = "changed.png"), record.copy(sizeBytes = 21), record.copy(modifiedAt = 2), record.copy(stableRef = "/other/image.png")))
            assertThat(store.read(changed, false)).isNull()
    }
    @Test fun corruptAndOversizedHintsAreIgnored() {
        val dir = temporary.newFolder()
        val store = ImageAttemptStore(dir)
        val record = ImageReviewBatchTest.records(1).single()
        store.write(record, false)
        dir.listFiles()!!.single().writeBytes(ByteArray(10))
        assertThat(ImageAttemptStore(dir).read(record, false)).isNull()
        dir.listFiles()!!.single().writeBytes(ByteArray(128))
        assertThat(ImageAttemptStore(dir).read(record, false)).isNull()
    }
    @Test fun unavailableStorageDoesNotThrowOrCreateContentEvidence() {
        val path = temporary.newFile()
        val store = ImageAttemptStore(path)
        val record = ImageReviewBatchTest.records(1).single()
        assertThat(store.write(record, false)).isFalse()
        assertThat(store.read(record, false)).isNull()
    }
    @Test fun interruptedTailRetainsEarlierProgressAndNextWriteRepairsIt() {
        val dir = temporary.newFolder()
        val records = ImageReviewBatchTest.records(2)
        val store = ImageAttemptStore(dir) { 12 }
        store.write(records[0], false, ImageAttemptOutcome.UNAVAILABLE)
        val file = dir.listFiles()!!.single()
        file.appendBytes(ByteArray(20) { 99 })
        val restarted = ImageAttemptStore(dir) { 13 }
        assertThat(restarted.read(records[0], false)?.outcome).isEqualTo(ImageAttemptOutcome.UNAVAILABLE)
        assertThat(restarted.write(records[1], true, ImageAttemptOutcome.SUCCEEDED)).isTrue()
        val again = ImageAttemptStore(dir)
        assertThat(again.read(records[0], false)?.at).isEqualTo(12)
        assertThat(again.read(records[1], true)?.at).isEqualTo(13)
        assertThat(file.length()).isEqualTo(96)
    }
    @Test fun checksumRejectsCorruptTailWithoutLosingVerifiedPrefix() {
        val dir = temporary.newFolder()
        val records = ImageReviewBatchTest.records(2)
        val store = ImageAttemptStore(dir)
        records.forEach { store.write(it, false) }
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[60] = (bytes[60].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val restarted = ImageAttemptStore(dir)
        assertThat(restarted.read(records[0], false)).isNotNull()
        assertThat(restarted.read(records[1], false)).isNull()
    }
    @Test fun manyProgressHintsUseOneSmallFile() {
        val dir = temporary.newFolder()
        val store = ImageAttemptStore(dir)
        ImageReviewBatchTest.records(1_000).forEach { store.write(it, false) }
        assertThat(dir.listFiles()!!.size).isEqualTo(1)
        assertThat(dir.listFiles()!!.single().length()).isEqualTo(46_004)
    }

}
