package com.pocketsteward.app.ui.onboarding

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.storage.StorageAccessMode
import org.junit.Test

class StorageAccessGrantPolicyTest {
    @Test
    fun directChoiceRequiresCurrentAllFilesGrant() {
        val state = StorageAccessState(mode = StorageAccessMode.DIRECT)

        assertThat(
            StorageAccessGrantPolicy.isUsable(
                state,
                broadAccessGranted = true,
                safReadGranted = false,
                safWriteGranted = false,
            ),
        ).isTrue()

        assertThat(
            StorageAccessGrantPolicy.isUsable(
                state,
                broadAccessGranted = false,
                safReadGranted = false,
                safWriteGranted = false,
            ),
        ).isFalse()
    }

    @Test
    fun selectedFolderChoiceRequiresPersistedReadAndWriteGrant() {
        val state = StorageAccessState(
            mode = StorageAccessMode.SAF,
            safTreeUri = "content://provider/tree/root",
        )

        assertThat(
            StorageAccessGrantPolicy.isUsable(
                state,
                broadAccessGranted = false,
                safReadGranted = true,
                safWriteGranted = true,
            ),
        ).isTrue()

        assertThat(
            StorageAccessGrantPolicy.isUsable(
                state,
                broadAccessGranted = false,
                safReadGranted = true,
                safWriteGranted = false,
            ),
        ).isFalse()
    }

    @Test
    fun selectedFolderChoiceWithoutUriIsNeverUsable() {
        val state = StorageAccessState(mode = StorageAccessMode.SAF, safTreeUri = null)

        assertThat(
            StorageAccessGrantPolicy.isUsable(
                state,
                broadAccessGranted = false,
                safReadGranted = true,
                safWriteGranted = true,
            ),
        ).isFalse()
    }

    @Test
    fun noPersistedChoiceDoesNotAutoSelectBroadAccess() {
        assertThat(
            StorageAccessGrantPolicy.isUsable(
                StorageAccessState(),
                broadAccessGranted = true,
                safReadGranted = false,
                safWriteGranted = false,
            ),
        ).isFalse()
    }
}
