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
    fun malformedValueFailsClosed() {
        assertThat(LastScanSessionCodec.decode("garbage")).isNull()
    }
}
