package com.pocketsteward.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the packaged JNI library on Android, including its defensive input boundary. */
@RunWith(AndroidJUnit4::class)
class NoReplaceMoveAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun unicodeDirectoriesAndMalformedPathsDoNotCrashOrOverwrite() {
        val root = File(context.cacheDir, "native-move-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "資料-🦇").apply { mkdir() }
            File(source, "原稿.txt").writeText("keep")
            val destination = File(root, "Lilith-✨")
            assertEquals(0, NoReplaceMove.move(source, destination))
            source.mkdir()
            assertTrue(NoReplaceMove.move(source, destination) != 0)
            assertEquals("keep", File(destination, "原稿.txt").readText())
            val native = NoReplaceMove::class.java.getDeclaredMethod("renamePaths", ByteArray::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
            for (bad in listOf(byteArrayOf(), byteArrayOf(0), ByteArray(8_192) { 120 })) {
                assertTrue((native.invoke(null, bad, destination.absolutePath.toByteArray(Charsets.UTF_8)) as Int) != 0)
                assertTrue(destination.isDirectory)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun competingExternalCreatesNeverGetReplaced() {
        val root = File(context.cacheDir, "native-race-${System.nanoTime()}").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { index ->
                val source = File(root, "s$index").apply { writeText("approved") }
                val destination = File(root, "d$index")
                val start = CountDownLatch(1)
                val moving = pool.submit<Int> { start.await(); NoReplaceMove.move(source, destination) }
                val creating = pool.submit<Boolean> {
                    start.await()
                    try {
                        Files.write(destination.toPath(), "external".toByteArray(), StandardOpenOption.CREATE_NEW)
                        true
                    } catch (_: FileAlreadyExistsException) { false }
                }
                start.countDown()
                val result = moving.get(10, TimeUnit.SECONDS)
                if (creating.get(10, TimeUnit.SECONDS)) {
                    assertTrue(result != 0)
                    assertTrue(source.exists())
                    assertEquals("external", destination.readText())
                } else {
                    assertEquals(0, result)
                    assertFalse(source.exists())
                    assertEquals("approved", destination.readText())
                }
            }
        } finally { pool.shutdownNow(); root.deleteRecursively() }
    }
}
