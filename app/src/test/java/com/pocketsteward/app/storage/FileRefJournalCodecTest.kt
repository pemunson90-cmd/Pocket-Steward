package com.pocketsteward.app.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileRefJournalCodecTest {
    @Test
    fun `round trips direct refs`() {
        val ref = FileRef.Direct("/storage/emulated/0/Download/a file.apk")
        assertThat(FileRefJournalCodec.decode(FileRefJournalCodec.encode(ref))).isEqualTo(ref)
    }

    @Test
    fun `round trips SAF refs containing colons`() {
        val ref = FileRef.Saf("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Ffoo")
        assertThat(FileRefJournalCodec.decode(FileRefJournalCodec.encode(ref))).isEqualTo(ref)
    }
}
