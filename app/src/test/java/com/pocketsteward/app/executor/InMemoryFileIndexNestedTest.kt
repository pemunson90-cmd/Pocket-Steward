package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class InMemoryFileIndexNestedTest {
    @Test fun symbolicNestedTreeDestinationDetectsExistingOpaqueChildCollision() {
        val root = FileRef.Saf("content://provider/document/root")
        val index = InMemoryFileIndex(listOf(row("album", "Album", root.documentUri, true), row("track", "Song.MP3", "content://provider/document/album", false)))
        val album = FileRef.Child(root, "album")
        assertThat(index.caseInsensitiveMatch(album, "song.mp3", null)).isEqualTo(FileRef.Saf("content://provider/document/track"))
        assertThat(index.exists(FileRef.Child(album, "song.mp3"))).isTrue()
        assertThat(index.isDirectory(album)).isTrue()
        assertThat(index.caseInsensitiveMatch(album, "song.mp3", FileRef.Saf("content://provider/document/track"))).isNull()
    }
    private fun row(id: String, name: String, parent: String, directory: Boolean) = FileRecord(stableRef = "content://provider/document/$id", displayName = name, extension = name.substringAfterLast('.', ""), mimeType = null, absolutePathOrUri = "content://provider/document/$id", parentRef = parent, sizeBytes = 1, createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = directory, isHidden = false)
}
