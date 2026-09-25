package com.pocketsteward.app.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectStorageGatewayCopyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root: File
    private lateinit var gateway: DirectStorageGateway

    @Before
    fun setUp() {
        root = DirectStorageTestFixture.freshRoot(context, "copy-test")
        gateway = DirectStorageGateway(context)
    }

    @After
    fun tearDown() {
        DirectStorageTestFixture.clean(root)
    }

    @Test
    fun copyPreservesSourceAndWritesExactDestinationBytes() = runBlocking {
        val source = File(root, "source.bin").apply {
            writeBytes(ByteArray(32 * 1024) { index -> (index % 251).toByte() })
        }
        val destinationParent = File(root, "nested").apply { mkdirs() }
        val destination = File(destinationParent, "copied.bin")

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertTrue(result is MutationResult.Success)
        assertTrue(source.exists())
        assertTrue(destination.exists())
        assertArrayEquals(source.readBytes(), destination.readBytes())
    }

    @Test
    fun copyRefusesOverwriteAndLeavesBothFilesUntouched() = runBlocking {
        val source = File(root, "source.txt").apply { writeText("source") }
        val destination = File(root, "destination.txt").apply { writeText("existing") }

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertTrue(result is MutationResult.Failure)
        assertEquals("source", source.readText())
        assertEquals("existing", destination.readText())
    }

    @Test
    fun copyRefusesDirectorySources() = runBlocking {
        val source = File(root, "folder").apply { mkdirs() }
        val destination = File(root, "folder-copy")

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertTrue(result is MutationResult.Failure)
        assertFalse(destination.exists())
    }
    @Test
    fun copyRefusesMissingParentInsteadOfCreatingUnjournaledDirectory() = runBlocking {
        val source = File(root, "source.txt").apply { writeText("source") }
        val missingParent = File(root, "missing")
        val destination = File(missingParent, "copy.txt")

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertTrue(result is MutationResult.Failure)
        assertTrue(source.exists())
        assertFalse(missingParent.exists())
        assertFalse(destination.exists())
    }

}
