package com.pocketsteward.app.content

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Enforces the byte limit even when a provider reports a false or unknown length. */
suspend fun copyInspectionSource(input: InputStream, output: OutputStream, limit: Long = 20L * 1024 * 1024) {
    require(limit >= 0)
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var emptyReads = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied + 1).toInt())
        if (read < 0) return
        if (read == 0) { require(++emptyReads < 32) { "Content provider did not make progress." }; continue }
        emptyReads = 0
        copied += read
        require(copied <= limit) { "Source exceeds the inspection byte limit." }
        output.write(buffer, 0, read)
    }
}
