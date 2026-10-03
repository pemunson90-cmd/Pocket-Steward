package com.pocketsteward.app.metadata

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Copies a non-seekable archive source into one uniquely named temporary file in
 * an app-private [directory] so header parsers can seek. Only compressed source
 * bytes are copied (never decoded member data), at most [budget] of them. The
 * file this call creates is the only file it deletes, on every exit path.
 */
internal object ArchiveStaging {
    const val DIRECTORY_NAME = "archive-inspection-staging"

    fun <T> withStagedPrefix(
        input: InputStream,
        directory: File,
        budget: Long,
        checkCancelled: () -> Unit,
        block: (staged: File, prefixOnly: Boolean) -> T,
    ): T {
        checkCancelled()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Staging directory is unavailable.")
        val staged = File.createTempFile("inspect-", ".part", directory)
        try {
            var copied = 0L
            var prefixOnly = false
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val want = minOf(buffer.size.toLong(), budget - copied).toInt()
                    if (want == 0) {
                        // One probe byte tells a complete copy from a budget-truncated prefix; it is not stored.
                        prefixOnly = input.read() >= 0
                        break
                    }
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
            checkCancelled()
            return block(staged, prefixOnly)
        } finally {
            staged.delete()
        }
    }
}
