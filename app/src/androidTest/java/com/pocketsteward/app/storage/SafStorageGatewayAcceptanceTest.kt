package com.pocketsteward.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafStorageGatewayAcceptanceTest {
    private lateinit var context: Context
    private lateinit var gateway: SafStorageGateway
    private lateinit var root: FileRef

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.contentResolver.call(
            Uri.parse("content://${TestSafDocumentsProvider.AUTHORITY}"),
            TestSafDocumentsProvider.METHOD_RESET,
            null,
            null,
        )
        gateway = SafStorageGateway(context)
        val tree = DocumentsContract.buildTreeDocumentUri(
            TestSafDocumentsProvider.AUTHORITY,
            TestSafDocumentsProvider.ROOT_ID,
        )
        root = gateway.rootOf(
            StorageScope.Tree(
                rootRef = FileRef.Saf(tree.toString()),
                displayName = "Test SAF",
            ),
        )
    }

    @After
    fun tearDownPermissions() {
    }

    @Test
    fun selectedTree_createWriteReadCopyMoveRenameTrashAndRestore_roundTrips() = runBlocking {
        val docs = gateway.createDirectory(root, "Docs")
        assertTrue(docs is MutationResult.Success)
        val docsRef = (docs as MutationResult.Success).resultRef

        val written = gateway.writeTextFile(docsRef, "source.txt", "alpha beta gamma")
        assertTrue(written is MutationResult.Success)
        val sourceRef = (written as MutationResult.Success).resultRef
        assertEquals("alpha beta gamma", readText(sourceRef))

        val copyDestination = FileRef.Child(docsRef, "copy.txt")
        val copied = gateway.copy(sourceRef, copyDestination)
        assertTrue(copied is MutationResult.Success)
        val copiedRef = (copied as MutationResult.Success).resultRef
        assertTrue(gateway.exists(sourceRef))
        assertEquals("alpha beta gamma", readText(copiedRef))

        val moveDestination = FileRef.Child(docsRef, "moved.txt")
        val moved = gateway.move(sourceRef, moveDestination)
        assertTrue(moved is MutationResult.Success)
        val movedRef = (moved as MutationResult.Success).resultRef
        assertFalse(gateway.exists(sourceRef))
        assertEquals("alpha beta gamma", readText(movedRef))

        val renamed = gateway.rename(movedRef, "renamed.txt")
        assertTrue(renamed is MutationResult.Success)
        val renamedRef = (renamed as MutationResult.Success).resultRef
        assertFalse(gateway.exists(movedRef))
        assertEquals("alpha beta gamma", readText(renamedRef))

        val trashed = gateway.trash(renamedRef)
        assertTrue(trashed is MutationResult.Success)
        val trashRef = (trashed as MutationResult.Success).resultRef
        assertFalse(gateway.exists(renamedRef))
        assertTrue(gateway.exists(trashRef))
        assertEquals("alpha beta gamma", readText(trashRef))

        val restored = gateway.move(
            trashRef,
            FileRef.Child(docsRef, "renamed.txt"),
        )
        assertTrue(restored is MutationResult.Success)
        val restoredRef = (restored as MutationResult.Success).resultRef
        assertFalse(gateway.exists(trashRef))
        assertEquals("alpha beta gamma", readText(restoredRef))
        assertEquals("alpha beta gamma", readText(copiedRef))
    }

    @Test
    fun symbolicNestedChildrenResolveAfterParentsAreCreated() = runBlocking {
        val plannedA = FileRef.Child(root, "A")
        val plannedB = FileRef.Child(plannedA, "B")

        assertTrue(gateway.createDirectory(root, "A") is MutationResult.Success)
        assertTrue(gateway.createDirectory(plannedA, "B") is MutationResult.Success)

        val written = gateway.writeTextFile(plannedB, "nested.md", "# nested")
        assertTrue(written is MutationResult.Success)
        assertEquals("# nested", readText((written as MutationResult.Success).resultRef))
    }

    @Test
    fun selectedTreeNeverOverwritesExistingDestination() = runBlocking {
        val docs = (gateway.createDirectory(root, "Docs") as MutationResult.Success).resultRef
        val a = (gateway.writeTextFile(docs, "a.txt", "one") as MutationResult.Success).resultRef
        val b = (gateway.writeTextFile(docs, "b.txt", "two") as MutationResult.Success).resultRef

        val result = gateway.copy(a, FileRef.Child(docs, "b.txt"))

        assertTrue(result is MutationResult.Failure)
        assertEquals("one", readText(a))
        assertEquals("two", readText(b))
    }

    @Test
    fun emptyDirectoryUndoPrimitiveRemovesOnlyEmptyDirectory() = runBlocking {
        val empty = (gateway.createDirectory(root, "Empty") as MutationResult.Success).resultRef
        val removed = gateway.removeEmptyDirectory(empty)
        assertTrue(removed is MutationResult.Success)
        assertFalse(gateway.exists(empty))

        val notEmpty = (gateway.createDirectory(root, "NotEmpty") as MutationResult.Success).resultRef
        gateway.writeTextFile(notEmpty, "keep.txt", "keep")
        val refused = gateway.removeEmptyDirectory(notEmpty)
        assertTrue(refused is MutationResult.Failure)
        assertTrue(gateway.exists(notEmpty))
    }

    private suspend fun readText(ref: FileRef): String =
        gateway.openRead(ref).use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }
}
