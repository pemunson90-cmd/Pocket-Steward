package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ScanReadPolicyTest {
    @Test fun privateAppContainersAreExcludedSegmentBySegment() {
        for (path in listOf("/storage/0/Android/data", "/storage/0/Android/data/app/cache", "/storage/0/Android/obb/app"))
            assertThat(ScanReadPolicy.excludedPrivateDirectory("/storage/0", FileRef.Direct(path))).isTrue()
    }
    @Test fun mediaAndUnrelatedNamedFoldersRemainReachable() {
        for (path in listOf("/storage/0/Android/media", "/storage/0/Android/database", "/storage/0/Download/Android/data", "/storage/00/Android/data"))
            assertThat(ScanReadPolicy.excludedPrivateDirectory("/storage/0", FileRef.Direct(path))).isFalse()
    }
    @Test fun grantedProviderNamesDoNotImplyAndroidPrivatePaths() {
        assertThat(ScanReadPolicy.excludedPrivateDirectory("/storage/0", FileRef.Saf("content://provider/tree/Android%3Adata"))).isFalse()
    }
}
