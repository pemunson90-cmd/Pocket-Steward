package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.*
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FilingReleaseDiscoveryTest {
    @Test fun observesMoreThanOneHundredRelevantHomesAndNestedRolesWithoutReadingOrMovingFiles() = runTest {
        val homes = (1..126).map { ProjectHomeCandidate("Project $it", "/Documents/Project $it", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
            roleFolders = mapOf("Versions" to "Releases/Builds", "Notes" to "Research/Notes")) }
        val dirs = homes.flatMap { home -> listOf(home.path, "${home.path}/RELEASES", "${home.path}/RELEASES/BUILDS",
            "${home.path}/RELEASES/BUILDS/v1.2.3", "${home.path}/RELEASES/BUILDS/v1.2.3/research", "${home.path}/RELEASES/BUILDS/v1.2.3/research/NOTES") }.toSet()
        val input = InboxFilingResult(homes.map(::decision))
        val observed = FilingReleaseDiscovery.discover(input, ReadOnlyGateway(dirs))
        assertThat(observed.unavailableHomes).isEmpty()
        assertThat(observed.directories).containsAtLeastElementsIn(dirs)
        val output = FilingReleaseConvention.reconcile(input, observed.directories, observed.unavailableHomes)
        assertThat(output.proposed).hasSize(126)
        assertThat(output.proposed.last().destinationDirectory).isEqualTo("/Documents/Project 126/RELEASES/BUILDS/v1.2.3/research/NOTES")
    }
    @Test fun unreadableHomeStaysUnresolvedWhileOtherHomesContinue() = runTest {
        val homes = listOf("A", "B").map { ProjectHomeCandidate(it, "/Documents/$it", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES) }
        val input = InboxFilingResult(homes.map(::decision))
        val observed = FilingReleaseDiscovery.discover(input, ReadOnlyGateway(homes.map { it.path }.toSet(), failure = SecurityException("revoked"), failPath = homes.first().path))
        val output = FilingReleaseConvention.reconcile(input, observed.directories, observed.unavailableHomes)
        assertThat(output.unresolved.single().projectName).isEqualTo("A")
        assertThat(output.proposed.single().projectName).isEqualTo("B")
    }
    @Test(expected = CancellationException::class) fun cancellationDoesNotMasqueradeAsMissingLayout() = runTest {
        val home = ProjectHomeCandidate("A", "/Documents/A", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        FilingReleaseDiscovery.discover(InboxFilingResult(listOf(decision(home))), ReadOnlyGateway(setOf(home.path), failure = CancellationException(), failPath = home.path))
    }
    private fun decision(home: ProjectHomeCandidate) = FilingDecision(
        FilingArtifact("/Downloads/${home.name}.txt", "notes.txt", "txt", 12, modifiedAt = 1, parentRef = "/Downloads"),
        home.name, home, "1.2.3", null, FilingConfidence.STRONG, emptyList(),
    ).let { it.copy(destinationDirectory = InboxFilingEngine.destinationFor(home, it.release, it.artifact)) }
    private class ReadOnlyGateway(private val dirs: Set<String>, private val failure: Exception? = null, private val failPath: String? = null) : StorageGateway {
        override suspend fun exists(ref: FileRef) = ref.rawValue() in dirs
        override suspend fun stat(ref: FileRef) = FileMetadata(ref, ref.rawValue().substringAfterLast('/'), "", null, 0, null, 1, true, false)
        override suspend fun listChildren(directory: FileRef): List<FileEntry> {
            if (directory.rawValue() == failPath) throw requireNotNull(failure)
            return dirs.filter { it.substringBeforeLast('/') == directory.rawValue() }.map { FileEntry(FileRef.Direct(it), it.substringAfterLast('/'), true, directory) }
        }
        override suspend fun rootOf(scope: StorageScope): FileRef = error("No authority")
        override suspend fun openRead(ref: FileRef): InputStream = error("No content reads")
        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult = error("Read-only")
        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult = error("Read-only")
        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun move(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun rename(source: FileRef, newName: String): MutationResult = error("Read-only")
        override suspend fun trashDestination(source: FileRef): FileRef = error("Read-only")
        override suspend fun trash(source: FileRef): MutationResult = error("Read-only")
        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult = error("Read-only")
    }
}
