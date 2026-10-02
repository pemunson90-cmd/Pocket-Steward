package com.pocketsteward.app.diagnostics

import java.util.Collections
import java.util.IdentityHashMap

/** Exception messages can contain document contents/paths; record only types and code frames. */
object CrashReport {
    const val MAX_CHARS = 24_000

    fun format(error: Throwable, version: String, device: String, route: String, mainThread: Boolean): String =
        buildString {
            appendLine("Pocket Steward crash record")
            appendLine("Version: ${version.take(100)}")
            appendLine("Device: ${device.take(160)}")
            appendLine("Screen: ${route.take(200)}")
            appendLine("Thread: ${if (mainThread) "main" else "background"}")
            appendLine("Exception messages and file contents are omitted.")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && seen.add(cause) && depth++ < 8) {
                appendLine(cause.javaClass.name)
                cause.stackTrace.take(48).forEach { frame ->
                    append("  at ").append(frame.className.take(160)).append('.')
                        .append(frame.methodName.take(120)).append(":").appendLine(frame.lineNumber)
                }
                cause = cause.cause
            }
        }.take(MAX_CHARS)
}
