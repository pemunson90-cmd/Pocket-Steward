package com.pocketsteward.app.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectStorageGatewayMutationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(context.cacheDir, "gateway-mutation-test")
    private lateinit var gateway: DirectStorageGateway

    @Before
    fun setUp() {
        root.deleteRecursively()
        check(root.mkdirs())
        gateway = DirectStorageGateway(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun createWriteRenameMoveAndCopyPreserveExpectedBytes() = runBlocking {
        val rootRef = FileRef.Direct(root.absolutePath)

        val created = gateway.createDirectory(rootRef, "資料-🦇")
        assertTrue(created is MutationResult.Success)
        val folder = (created as MutationResult.Success).resultRef

        val written = gateway.writeTextFile(
            parent = folder,
            name = "原稿.txt",
            content = "Pocket Steward\nUnicode survives.\n",
        )
        assertTrue(written is MutationResult.Success)
        val original = (written as MutationResult.Success).resultRef
        assertEquals(
            "Pocket Steward\nUnicode survives.\n",
            File(original.rawValue()).readText(),
        )

        val renamed = gateway.rename(original, "renamed-✨.txt")
        assertTrue(renamed is MutationResult.Success)
        val renamedRef = (renamed as MutationResult.Success).resultRef
        assertFalse(File(original.rawValue()).exists())
        assertTrue(File(renamedRef.rawValue()).isFile)

        val destinationDir = File(root, "destination").apply { mkdirs() }
        val movedPath = File(destinationDir, "renamed-✨.txt")
        val moved = gateway.move(
            renamedRef,
            FileRef.Direct(movedPath.absolutePath),
        )
        assertTrue(moved is MutationResult.Success)
        assertFalse(File(renamedRef.rawValue()).exists())
        assertEquals(
            "Pocket Steward\nUnicode survives.\n",
            movedPath.readText(),
        )

        val copiedPath = File(root, "copy/duplicate.txt")
        val copied = gateway.copy(
            FileRef.Direct(movedPath.absolutePath),
            FileRef.Direct(copiedPath.absolutePath),
        )
        assertTrue(copied is MutationResult.Success)
        assertTrue(movedPath.exists())
        assertEquals(movedPath.readBytes().toList(), copiedPath.readBytes().toList())
    }

    @Test
    fun everyMutationRefusesOverwrite() = runBlocking {
        val source = File(root, "source.txt").apply { writeText("source") }
        val occupied = File(root, "occupied.txt").apply { writeText("occupied") }

        val copy = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(occupied.absolutePath),
        )
        assertTrue(copy is MutationResult.Failure)
        assertEquals("source", source.readText())
        assertEquals("occupied", occupied.readText())

        val move = gateway.move(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(occupied.absolutePath),
        )
        assertTrue(move is MutationResult.Failure)
        assertEquals("source", source.readText())
        assertEquals("occupied", occupied.readText())

        val rename = gateway.rename(
            FileRef.Direct(source.absolutePath),
            occupied.name,
        )
        assertTrue(rename is MutationResult.Failure)
        assertEquals("source", source.readText())
        assertEquals("occupied", occupied.readText())

        val write = gateway.writeTextFile(
            FileRef.Direct(root.absolutePath),
            occupied.name,
            "replacement",
        )
        assertTrue(write is MutationResult.Failure)
        assertEquals("occupied", occupied.readText())
    }

    @Test
    fun createDirectoryIsIdempotentAndUndoRemovalRequiresEmptyDirectory() = runBlocking {
        val rootRef = FileRef.Direct(root.absolutePath)
        val first = gateway.createDirectory(rootRef, "generated")
        assertTrue(first is MutationResult.Success)
        assertTrue((first as MutationResult.Success).changed)

        val second = gateway.createDirectory(rootRef, "generated")
        assertTrue(second is MutationResult.Success)
        assertFalse((second as MutationResult.Success).changed)

        val dir = File(root, "generated")
        File(dir, "user-file.txt").writeText("keep me")
        val blocked = gateway.removeEmptyDirectory(FileRef.Direct(dir.absolutePath))
        assertTrue(blocked is MutationResult.Failure)
        assertTrue(dir.exists())

        File(dir, "user-file.txt").delete()
        val removed = gateway.removeEmptyDirectory(FileRef.Direct(dir.absolutePath))
        assertTrue(removed is MutationResult.Success)
        assertFalse(dir.exists())
    }
}
