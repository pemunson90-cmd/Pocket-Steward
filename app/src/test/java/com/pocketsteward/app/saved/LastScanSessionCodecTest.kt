package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.StorageAccessMode
import org.junit.Test

class LastScanSessionCodecTest {
    @Test
    fun directMultiRootRoundTrips() {
        val session = LastScanSession(
            mode = StorageAccessMode.DIRECT,
            roots = listOf(
                LastScanRoot("Downloads", "/storage/emulated/0/Download"),
                LastScanRoot("Docs;weird", "/storage/emulated/0/Documents/My Stuff"),
            ),
            savedAtEpochMs = 123L,
        )

        assertThat(LastScanSessionCodec.decode(LastScanSessionCodec.encode(session)))
            .isEqualTo(session)
    }

    @Test
    fun safUrisRoundTripWithoutDelimiterProblems() {
        val session = LastScanSession(
            mode = StorageAccessMode.SAF,
            roots = listOf(
                LastScanRoot(
                    "Selected folder",
                    "content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments",
                ),
            ),
            savedAtEpochMs = 456L,
        )

        assertThat(LastScanSessionCodec.decode(LastScanSessionCodec.encode(session)))
            .isEqualTo(session)
    }

    @Test
    fun completedSnapshotMatchesExactRootsRegardlessOfOrderOrTrailingSlash() {
        val session = LastScanSession(
            mode = StorageAccessMode.DIRECT,
            roots = listOf(
                LastScanRoot("Downloads", "/storage/emulated/0/Download"),
                LastScanRoot("Documents", "/storage/emulated/0/Documents/"),
            ),
            savedAtEpochMs = 789L,
        )

        assertThat(
            session.matchesScopeSet(
                StorageAccessMode.DIRECT,
                listOf("/storage/emulated/0/Documents", "/storage/emulated/0/Download/"),
            ),
        ).isTrue()
        assertThat(
            session.matchesScopeSet(
                StorageAccessMode.DIRECT,
                listOf("/storage/emulated/0/Download"),
            ),
        ).isFalse()
        assertThat(
            session.matchesScopeSet(
                StorageAccessMode.SAF,
                listOf("/storage/emulated/0/Download", "/storage/emulated/0/Documents"),
            ),
        ).isFalse()
    }

    @Test
    fun malformedValueFailsClosed() {
        assertThat(LastScanSessionCodec.decode("garbage")).isNull()
    }
}
