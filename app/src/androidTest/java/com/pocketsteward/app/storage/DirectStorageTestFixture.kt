package com.pocketsteward.app.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Gives DirectStorageGateway instrumentation tests a real shared-storage sandbox.
 * Internal cacheDir and Android/data are intentionally outside/behind the gateway's
 * production mutation boundary, so using them as mutation fixtures tests the wrong thing.
 */
internal object DirectStorageTestFixture {
    private const val BASE_DIR = ".PocketStewardInstrumentation"

    fun freshRoot(context: Context, name: String): File {
        ensureAllFilesAccess(context)
        val base = File(Environment.getExternalStorageDirectory(), BASE_DIR)
        val root = File(base, name)
        root.deleteRecursively()
        check(root.mkdirs() || root.isDirectory) {
            "Could not create direct-storage instrumentation root: ${root.absolutePath}"
        }
        return root
    }

    fun clean(root: File) {
        root.deleteRecursively()
        val base = root.parentFile
        if (base?.name == BASE_DIR && base.listFiles()?.isEmpty() == true) {
            base.delete()
        }
    }

    private fun ensureAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return

        val command = "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }

        repeat(20) {
            if (Environment.isExternalStorageManager()) return
            Thread.sleep(50)
        }
        check(Environment.isExternalStorageManager()) {
            "Direct-storage instrumentation requires MANAGE_EXTERNAL_STORAGE app-op"
        }
    }
}
