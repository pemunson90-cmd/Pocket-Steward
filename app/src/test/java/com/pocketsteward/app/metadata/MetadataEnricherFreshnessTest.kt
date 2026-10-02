package com.pocketsteward.app.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MetadataEnricherFreshnessTest {
    @Test fun damagedReplacementApkCannotInheritPreviouslyIndexedPackageOwnership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "invalid-${UUID.randomUUID()}.apk").apply { writeText("Unparseable replacement APK") }
        val record = FileRecord(stableRef = source.absolutePath, displayName = source.name, extension = "apk", mimeType = "application/vnd.android.package-archive",
            absolutePathOrUri = source.absolutePath, parentRef = source.parent, sizeBytes = source.length(), createdAt = null, modifiedAt = source.lastModified(),
            lastScannedAt = 0, isDirectory = false, isHidden = false, apkPackageName = "old.project.package", apkVersionName = "1.0")
        try {
            val inspected = MetadataEnricher(context).enrich(record)
            assertThat(inspected.record.apkPackageName).isNull()
            assertThat(inspected.record.apkVersionName).isNull()
            assertThat(inspected.changed).isTrue()
        } finally { source.delete() }
    }
}
