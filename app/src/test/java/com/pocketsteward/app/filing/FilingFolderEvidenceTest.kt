package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class FilingFolderEvidenceTest {
    private val root = "/storage/emulated/0"
    private val folder = FilingArtifact("$root/Download/bundle", "bundle", "", 0, modifiedAt = 99,
        parentRef = "$root/Download", isDirectory = true)
    private val lilith = ProjectHomeCandidate("Lilith", "$root/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true)
    private val nstl = lilith.copy(name = "NSTL", path = "$root/Documents/NSTL")
    private fun unresolved() = FilingDecision(folder, null, null, null, null, FilingConfidence.UNRESOLVED, emptyList())
    private fun child(name: String, home: ProjectHomeCandidate, kind: FilingEvidenceKind = FilingEvidenceKind.FILENAME): FilingDecision {
        val artifact = FilingArtifact("${folder.stableRef}/$name", name, name.substringAfterLast('.'), 100, modifiedAt = 99, parentRef = folder.stableRef)
        return FilingDecision(artifact, home.name, home, null, InboxFilingEngine.destinationFor(home, null, artifact),
            FilingConfidence.STRONG, listOf(FilingEvidence(kind, "Observed project evidence", 90)))
    }
    private fun reconcile(original: FilingDecision = unresolved(), children: List<FilingDecision>): FilingDecision =
        FilingFolderEvidence.reconcile(InboxFilingResult(listOf(original)), InboxFilingResult(children),
            children.associate { it.artifact.stableRef to folder.stableRef }, mapOf(folder.stableRef to children.size),
            mapOf(folder.stableRef to children.size)).decisions.single()

    @Test fun containedProjectFilesSuggestOneIntactUncheckedMoveWithoutScatteringRoles() {
        val resolved = reconcile(children = listOf(child("Lilith-manuscript.txt", lilith), child("Lilith-cover.png", lilith)))
        assertThat(resolved.projectHome).isEqualTo(lilith)
        assertThat(resolved.destinationDirectory).isEqualTo(lilith.path)
        assertThat(resolved.confidence).isEqualTo(FilingConfidence.PROBABLE)
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(resolved)), FileRef.Direct(root), setOf(lilith.path))
        val moves = plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves).hasSize(1)
        assertThat(moves.single().source).isEqualTo(FileRef.Direct(folder.stableRef))
        assertThat(moves.single().destination).isEqualTo(FileRef.Direct("${lilith.path}/bundle"))
        assertThat(plan.defaultSelectedSourceRefs).isEmpty()
    }

    @Test fun mixedProjectsAndContradictoryFolderNamesRequireAnOwnerChoice() {
        val mixed = reconcile(children = listOf(child("Lilith.txt", lilith), child("NSTL.txt", nstl)))
        assertThat(mixed.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        assertThat(mixed.destinationDirectory).isNull()
        val named = unresolved().copy(projectName = nstl.name, projectHome = nstl, destinationDirectory = nstl.path, confidence = FilingConfidence.STRONG)
        val contradiction = reconcile(named, listOf(child("Lilith.txt", lilith)))
        assertThat(contradiction.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        assertThat(contradiction.evidence.any { it.kind == FilingEvidenceKind.PROJECT_AMBIGUITY }).isTrue()
    }

    @Test fun ambiguousChildEvidenceCannotDisappearBehindAnotherChildsOwner() {
        val ambiguous = child("mixed.txt", lilith).copy(projectName = null, projectHome = null, confidence = FilingConfidence.UNRESOLVED,
            evidence = listOf(FilingEvidence(FilingEvidenceKind.PROJECT_AMBIGUITY, "Two homes match", 200)))
        assertThat(reconcile(children = listOf(child("Lilith.txt", lilith), ambiguous)).confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        assertThat(reconcile(children = listOf(ambiguous)).confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    @Test fun timeProximityAndGenericMediaCategoriesCannotOwnAFolder() {
        val temporal = child("notes.txt", lilith, FilingEvidenceKind.COHORT)
        val landscape = lilith.copy(name = "Landscape", path = "$root/Images/Landscape", categoryHome = true)
        assertThat(reconcile(children = listOf(temporal, child("landscape.png", landscape))).projectHome).isNull()
    }

    @Test fun rememberedFolderOwnershipRemainsExplicitWithAMixedContentsWarning() {
        val remembered = unresolved().copy(projectName = lilith.name, projectHome = lilith, destinationDirectory = lilith.path,
            confidence = FilingConfidence.STRONG, evidence = listOf(FilingEvidence(FilingEvidenceKind.USER_MAPPING, "Chosen owner", 120)))
        val resolved = reconcile(remembered, listOf(child("NSTL.txt", nstl)))
        assertThat(resolved.projectHome).isEqualTo(lilith)
        assertThat(resolved.evidence.any { it.kind == FilingEvidenceKind.PROJECT_AMBIGUITY }).isTrue()
    }
}
