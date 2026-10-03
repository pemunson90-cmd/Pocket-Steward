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
    @Test fun olderInspectorArchiveResultsAreNotReusedButUnrelatedEntriesAre() {
        val rar = record().copy(stableRef = "/inbox/bundle.rar", displayName = "bundle.rar", extension = "rar")
        val photo = record().copy(stableRef = "/inbox/photo.jpg", displayName = "photo.jpg", extension = "jpg")
        // Written exactly as the pre-revision cache did (RAR was not inspected then).
        val legacy = MetadataEvidenceCache(folder.root, archiveInspectorRevision = null)
        legacy.write(MetadataEnrichment(rar, false, archiveComplete = false, archiveNote = "This archive format is not supported."), "sample-1")
        legacy.write(MetadataEnrichment(photo.copy(width = 4, height = 3), true, exifCamera = "Pixel"), "sample-1")
        val current = MetadataEvidenceCache(folder.root)
        assertThat(current.read(rar, "sample-1")).isNull()
        assertThat(current.read(photo, "sample-1")?.exifCamera).isEqualTo("Pixel")
        // A newer inspector revision also invalidates entries from the current one.
        current.write(MetadataEnrichment(rar, false, archiveEntryCount = 9, archiveSample = listOf("NSTL/README.md"), archiveComplete = true), "sample-1")
        assertThat(current.read(rar, "sample-1")?.archiveSample).containsExactly("NSTL/README.md")
        assertThat(MetadataEvidenceCache(folder.root, ArchiveInspector.REVISION + 1).read(rar, "sample-1")).isNull()
        // Freshness checks still apply on top of the revision.
        assertThat(current.read(rar, "sample-2")).isNull()
        assertThat(current.read(rar.copy(modifiedAt = 2), "sample-1")).isNull()
    }
    private fun record() = FileRecord(stableRef = "/inbox/a.apk", displayName = "a.apk", extension = "apk", mimeType = null,
        absolutePathOrUri = "/inbox/a.apk", parentRef = "/inbox", sizeBytes = 10, createdAt = null, modifiedAt = 1,
        lastScannedAt = 1, isDirectory = false, isHidden = false)
}
