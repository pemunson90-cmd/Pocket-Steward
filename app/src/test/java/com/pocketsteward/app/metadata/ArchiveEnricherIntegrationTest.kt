package com.pocketsteward.app.metadata

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArchiveEnricherIntegrationTest {
    private lateinit var app: Application
    private lateinit var provider: ArchiveDescriptorProvider

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        provider = ArchiveDescriptorProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "archive.inspection.fixture"
            name = ArchiveDescriptorProvider::class.java.name
            packageName = app.packageName
            applicationInfo = app.applicationInfo
            exported = true
            grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("archive.inspection.fixture", provider)
    }

    @Test fun directAndContentDescriptorArchivesProduceTheSameVerifiedMemberEvidence() {
        for (name in listOf("project-release-rar4.rar", "project-release-rar5.rar", "project-release.7z")) {
            val source = fixture(name)
            provider.source = source
            val direct = MetadataEnricher(app).enrich(record(source, source.absolutePath))
            val selectedTree = MetadataEnricher(app).enrich(record(source, "content://archive.inspection.fixture/${UUID.randomUUID()}/$name"))
            assertThat(selectedTree.archiveSample).isEqualTo(direct.archiveSample)
            assertThat(selectedTree.archiveEntryCount).isEqualTo(9)
            assertThat(selectedTree.archiveComplete).isTrue()
            assertThat(selectedTree.archiveNote).isNull()
            assertThat(selectedTree.archiveSample).contains("NSTL/NSTL-1.4.0-dev18/release/NSTL-1.4.0-dev18.apk")
            assertThat(File(app.cacheDir, ArchiveStaging.DIRECTORY_NAME).list()?.toList().orEmpty()).isEmpty()
        }
    }

    @Test fun cancellationDuringDescriptorInspectionDoesNotPublishOrCachePartialEvidence() {
        val source = fixture("entries-2001-rar5.rar")
        provider.source = source
        val candidate = record(source, "content://archive.inspection.fixture/${UUID.randomUUID()}/many.rar")
        var checks = 0
        val enricher = MetadataEnricher(app)
        assertThrows(CancellationException::class.java) {
            enricher.enrich(candidate) {
                if (provider.opens >= 2 && ++checks == 10) throw CancellationException("Cancelled review")
            }
        }
        assertThat(checks).isEqualTo(10)
        val result = enricher.enrich(candidate)
        assertThat(result.archiveEntryCount).isEqualTo(2_000)
        assertThat(result.archiveSample).hasSize(40)
        assertThat(result.archiveNote).contains("Entry limit")
        assertThrows(CancellationException::class.java) {
            enricher.enrich(candidate) { throw CancellationException("Cancelled cached request") }
        }
    }

    @Test fun nonProgressingChannelAndStreamReturnInsteadOfSpinning() {
        val bytes = fixture("project-release.7z").readBytes()
        val channel = object : SeekableInMemoryByteChannel(bytes) {
            override fun read(destination: ByteBuffer): Int = 0
        }
        assertThat(ArchiveInspector.channel(channel, "7z").complete).isFalse()
        val directory = File(app.cacheDir, "zero-progress-${UUID.randomUUID()}")
        val stream = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        val result = ArchiveInspector.stream(stream, "rar", directory)
        assertThat(result.complete).isFalse()
        assertThat(result.note).contains("could not be staged")
        assertThat(directory.list()?.toList()).isEmpty()
    }

    private fun fixture(name: String): File = File(requireNotNull(javaClass.classLoader!!.getResource("archive-fixtures/$name")).toURI())
    private fun record(file: File, ref: String) = FileRecord(
        stableRef = ref, displayName = file.name, extension = file.extension,
        mimeType = null, absolutePathOrUri = ref, parentRef = null,
        sizeBytes = file.length(), createdAt = null, modifiedAt = file.lastModified(),
        lastScannedAt = 0, isDirectory = false, isHidden = false,
    )
}

class ArchiveDescriptorProvider : ContentProvider() {
    lateinit var source: File
    var opens = 0
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r")
        opens++
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
}
