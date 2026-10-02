package com.pocketsteward.app.metadata

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataEvidenceCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun cacheRetainsTransientEvidenceAndRejectsChangedOrUnverifiableSources() {
        val cache = MetadataEvidenceCache(folder.root)
        val record = record()
        cache.write(MetadataEnrichment(record.copy(apkPackageName = "example.app"), true,
            archiveSample = listOf("Lilith/notes.md"), apkLabel = "Lilith", apkVersionCode = 42), "sample-1")
        val read = cache.read(record, "sample-1")!!
        assertThat(read.apkLabel).isEqualTo("Lilith")
        assertThat(read.archiveSample).containsExactly("Lilith/notes.md")
        assertThat(read.record.apkPackageName).isEqualTo("example.app")
        assertThat(cache.read(record.copy(modifiedAt = 2), "sample-1")).isNull()
        assertThat(cache.read(record.copy(sizeBytes = 11), "sample-1")).isNull()
        assertThat(cache.read(record.copy(modifiedAt = null), "sample-1")).isNull()
    }
    @Test fun corruptDerivedCacheIsIgnored() {
        val cache = MetadataEvidenceCache(folder.root)
        cache.write(MetadataEnrichment(record(), false), "sample-1")
        folder.root.listFiles()!!.single().writeText("bad data")
        assertThat(cache.read(record(), "sample-1")).isNull()
    }
    @Test fun sameSizeAndDateCannotReuseEvidenceWithoutMatchingLiveByteSample() {
        val cache = MetadataEvidenceCache(folder.root)
        val record = record()
        cache.write(MetadataEnrichment(record, false, archiveSample = listOf("Lilith/manuscript.md")), "sample-before")
        assertThat(cache.read(record, "sample-before")?.archiveSample).containsExactly("Lilith/manuscript.md")
        assertThat(cache.read(record, "sample-after")).isNull()
        assertThat(cache.read(record, null)).isNull()
    }
    private fun record() = FileRecord(stableRef = "/inbox/a.apk", displayName = "a.apk", extension = "apk", mimeType = null,
        absolutePathOrUri = "/inbox/a.apk", parentRef = "/inbox", sizeBytes = 10, createdAt = null, modifiedAt = 1,
        lastScannedAt = 1, isDirectory = false, isHidden = false)
}
