package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectoryRefreshPolicyTest {
    @Test fun parentNoticeCoversNestedCheckpointWithoutScanningItTwice() {
        assertThat(DirectoryRefreshPolicy.select("/storage/0", listOf("/storage/0/Download/Uncertain", "/storage/0/Download", "/storage/0/Download/")))
            .containsExactly("/storage/0/Download")
    }
    @Test fun unrelatedStorageAndTraversalTriggerFullReconciliationInsteadOfGrantingNewScope() {
        for (path in listOf("/storage/00/Download", "/storage/0/../private", "/storage/0//Download", "/storage/0/Download/./x", "relative", "/storage/0"))
            assertThat(DirectoryRefreshPolicy.select("/storage/0", listOf(path))).isNull()
    }
    @Test fun canonicalStorageAliasKeepsInventoryKeysInTheLibraryNamespace() {
        assertThat(DirectoryRefreshPolicy.select("/storage/0", listOf("/mnt/user/0/Download"), "/mnt/user/0")).containsExactly("/storage/0/Download")
    }
    @Test fun payloadIsBoundedByCountAndActualUtf8Bytes() {
        assertThat(DirectoryRefreshPolicy.select("/storage/0", List(65) { "/storage/0/$it" })).isNull()
        assertThat(DirectoryRefreshPolicy.select("/storage/0", listOf("/storage/0/" + "字".repeat(3_000)))).isNull()
        assertThat(DirectoryRefreshPolicy.select("/storage/0", emptyList())).isNull()
    }
    @Test fun onlyAncestorAndDescendantScopeMembershipsAreReconciled() {
        assertThat(DirectoryRefreshPolicy.overlappingScopes("/storage/0/Download", listOf("/storage/0", "/storage/0/Download", "/storage/0/Download/Uncertain", "/storage/0/Downloads2", "content://tree/x", "library-refresh:/storage/0/Download")))
            .containsExactly("/storage/0", "/storage/0/Download", "/storage/0/Download/Uncertain")
    }
}
