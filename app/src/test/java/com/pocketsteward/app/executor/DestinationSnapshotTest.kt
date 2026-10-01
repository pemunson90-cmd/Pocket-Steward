package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy

class DestinationSnapshotTest {
    @Test fun collisionAtEndOfLargeHomeIsRetainedWithoutWalkingUnrelatedFolders() = runBlocking {
        val root = FileRef.Direct("/storage")
        val docs = entry("/storage/Documents", true)
        val home = entry("/storage/Documents/NSTL", true)
        val notes = entry("/storage/Documents/NSTL/Notes", true)
        val wanted = FileRef.Direct("${notes.ref.rawValue()}/report.txt")
        val listed = mutableListOf<FileRef>()
        val children = mapOf(root to listOf(docs, entry("/storage/Unrelated", true)), docs.ref to listOf(home), home.ref to listOf(notes), notes.ref to ((0 until 6000).map { entry("${notes.ref.rawValue()}/old-$it.txt", false) } + entry(wanted.absolutePath, false)))
        val result = DestinationSnapshot.load(gateway(children, listed), root, listOf(PlannedOperation.Copy(FileRef.Direct("/inbox/report.txt"), wanted, "copy")))
        assertThat(result.map { it.ref }).contains(wanted)
        assertThat(result).hasSize(4)
        assertThat(listed).containsExactly(root, docs.ref, home.ref, notes.ref).inOrder()
        assertThat(LiveTreeFileIndex(root, result).exists(wanted)).isTrue()
    }
    @Test fun caseVariantOfExistingRoleFolderStillExposesItsFileCollision() = runBlocking {
        val root = FileRef.Direct("/home")
        val notes = entry("/home/notes", true)
        val file = entry("/home/notes/Report.TXT", false)
        val result = DestinationSnapshot.load(gateway(mapOf(root to listOf(notes), notes.ref to listOf(file)), mutableListOf()), root,
            listOf(PlannedOperation.Copy(FileRef.Direct("/inbox/report.txt"), FileRef.Direct("/home/Notes/report.txt"), "copy")))
        assertThat(LiveTreeFileIndex(root, result).exists(FileRef.Direct("/home/Notes/report.txt"))).isTrue()
    }
    @Test fun byteOrEntryLimitFailsClosedRatherThanReturningIncompleteIndex() = runBlocking {
        val root = FileRef.Direct("/home")
        val gateway = gateway(mapOf(root to listOf(entry("/home/a.txt", false), entry("/home/b.txt", false))), mutableListOf())
        val operations = listOf("a.txt", "b.txt").map { PlannedOperation.Copy(FileRef.Direct("/inbox/$it"), FileRef.Direct("/home/$it"), "copy") }
        val error = runCatching { DestinationSnapshot.load(gateway, root, operations, maxEntries = 1) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
    private fun entry(path: String, directory: Boolean) = FileEntry(FileRef.Direct(path), path.substringAfterLast('/'), directory, FileRef.Direct(path.substringBeforeLast('/')))
    private fun gateway(children: Map<FileRef, List<FileEntry>>, listed: MutableList<FileRef>): StorageGateway {
        val byRef = children.values.flatten().associateBy { it.ref }
        return Proxy.newProxyInstance(StorageGateway::class.java.classLoader, arrayOf(StorageGateway::class.java)) { _, method, args ->
            val ref = args?.firstOrNull() as? FileRef
            when (method.name) {
                "exists" -> ref in children || ref in byRef
                "stat" -> FileMetadata(requireNotNull(ref), ref.rawValue().substringAfterLast('/'), "", null, 0, null, 1, ref in children || byRef[ref]?.isDirectory == true, false)
                "listChildren" -> { listed += requireNotNull(ref); children.getValue(ref) }
                else -> error("Snapshot must not read contents or mutate files: ${method.name}")
            }
        } as StorageGateway
    }
}
