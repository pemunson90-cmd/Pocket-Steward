package com.pocketsteward.app.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalCrashDiagnosticsTest {
    @Test fun fatalHandlerRecordsLocallyAndAlwaysDelegatesOriginalException() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        val error = IllegalArgumentException("private manuscript content")
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegated = throwable }
            LocalCrashDiagnostics.install(app)
            LocalCrashDiagnostics.activeRoute = "files"
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            val report = LocalCrashDiagnostics.read(app)!!
            assertTrue(report.contains("IllegalArgumentException"))
            assertTrue(report.contains("Screen: files"))
            assertFalse(report.contains("private manuscript content"))
            LocalCrashDiagnostics.dismiss(app)
            assertNull(LocalCrashDiagnostics.read(app))
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }
}
