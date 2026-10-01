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
            archiveSample = listOf("Lilith/notes.md"), apkLabel = "Lilith", apkVersionCode = 42))
        val read = cache.read(record)!!
        assertThat(read.apkLabel).isEqualTo("Lilith")
        assertThat(read.archiveSample).containsExactly("Lilith/notes.md")
        assertThat(read.record.apkPackageName).isEqualTo("example.app")
        assertThat(cache.read(record.copy(modifiedAt = 2))).isNull()
        assertThat(cache.read(record.copy(sizeBytes = 11))).isNull()
        assertThat(cache.read(record.copy(modifiedAt = null))).isNull()
    }
    @Test fun corruptDerivedCacheIsIgnored() {
        val cache = MetadataEvidenceCache(folder.root)
        cache.write(MetadataEnrichment(record(), false))
        folder.root.listFiles()!!.single().writeText("bad data")
        assertThat(cache.read(record())).isNull()
    }
    private fun record() = FileRecord(stableRef = "/inbox/a.apk", displayName = "a.apk", extension = "apk", mimeType = null,
        absolutePathOrUri = "/inbox/a.apk", parentRef = "/inbox", sizeBytes = 10, createdAt = null, modifiedAt = 1,
        lastScannedAt = 1, isDirectory = false, isHidden = false)
}
