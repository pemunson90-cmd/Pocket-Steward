package com.pocketsteward.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CrashReportTest {
    @Test fun keepsCauseAndCodeFramesButOmitsExceptionMessages() {
        val cause = IllegalArgumentException("private file /Download/Lilith manuscript.txt")
        cause.stackTrace = arrayOf(StackTraceElement("ExampleNav", "lookup", "Nav.kt", 42))
        val failure = IllegalStateException("secret document text", cause)
        val report = CrashReport.format(failure, "dev17", "Fold 7", "scan_flow", true)
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("ExampleNav.lookup:42"))
        assertFalse(report.contains("Lilith"))
        assertFalse(report.contains("secret document text"))
    }

    @Test fun boundsLongAndCyclicCauseChains() {
        val first = RuntimeException()
        val second = RuntimeException()
        first.initCause(second)
        second.initCause(first)
        val longFrame = StackTraceElement("c".repeat(1000), "m".repeat(1000), null, 1)
        first.stackTrace = Array(1000) { longFrame }
        second.stackTrace = first.stackTrace
        val report = CrashReport.format(first, "v", "device", "files", false)
        assertTrue(report.length <= CrashReport.MAX_CHARS)
        assertTrue(report.contains("Thread: background"))
    }
}
