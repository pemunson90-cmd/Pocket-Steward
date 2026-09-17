package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class FileRefCodecTest {

    @Test
    fun `round-trips an empty queue`() {
        assertThat(FileRefCodec.decodeList(FileRefCodec.encodeList(emptyList()))).isEmpty()
    }

    @Test
    fun `round-trips a mix of Direct and Saf refs, including a URI with colons`() {
        val refs = listOf(
            FileRef.Direct("/storage/emulated/0/Download"),
            FileRef.Saf("content://com.android.externalstorage.documents/tree/primary%3ADownload"),
            FileRef.Direct("/storage/emulated/0/Download/sub folder"),
        )

        val roundTripped = FileRefCodec.decodeList(FileRefCodec.encodeList(refs))

        assertThat(roundTripped).isEqualTo(refs)
    }
}
