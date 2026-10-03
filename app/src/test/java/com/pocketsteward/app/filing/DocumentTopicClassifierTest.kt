package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.*
import com.pocketsteward.app.storage.*
import com.pocketsteward.app.plan.PlannedOperation
import org.junit.Test

class DocumentTopicClassifierTest {
    private val root = "/storage/emulated/0"
    private fun file(text: String = "Invoice\nPayment due after the bank statement is checked") = FilingArtifact(
        "$root/Download/scan_0042.pdf", "scan_0042.pdf", "pdf", 1000, modifiedAt = 1000, parentRef = "$root/Download", indexedText = text)
    private fun resolve(artifact: FilingArtifact, homes: List<ProjectHomeCandidate> = emptyList(),
        keywords: List<ProjectKeyword> = emptyList(), topics: DocumentTopicRules = DocumentTopicRules.Defaults) =
        InboxFilingEngine.resolve(listOf(artifact), homes, emptyList(), keywords, emptyList(), root, documentTopics = topics).decisions.single()

    @Test fun genericDocumentGetsAnUncheckedGroundedCategoryWithQuotedSourceEvidence() {
        val decision = resolve(file())
        assertThat(decision.destinationDirectory).isEqualTo("$root/Documents/Finance")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.PROBABLE)
        assertThat(decision.projectHome!!.categoryHome).isTrue()
        assertThat(decision.release).isNull()
        assertThat(decision.evidence.filter { it.kind == FilingEvidenceKind.DOCUMENT_TOPIC }.joinToString { it.detail }).contains("Payment due")
        val plan = InboxFilingPlanAdapter.build(InboxFilingResult(listOf(decision)), FileRef.Direct(root), emptySet())
        assertThat(plan.defaultSelectedSourceRefs).isEmpty()
        assertThat(plan.presentation.groups.single().isTopicDestination).isTrue()
    }
    @Test fun knownProjectsAndExplicitProjectHeadersOutrankDocumentTopics() {
        val home = ProjectHomeCandidate("Lilith", "$root/Books/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true)
        val owned = resolve(file("Project: Lilith\nInvoice\nPayment due"), listOf(home))
        assertThat(owned.projectHome).isEqualTo(home)
        assertThat(owned.evidence.none { it.kind == FilingEvidenceKind.DOCUMENT_TOPIC }).isTrue()
        val mapped = resolve(file("Invoice\nPayment due for the NSTL team"), keywords = listOf(ProjectKeyword("NSTL", "NSTL")))
        assertThat(mapped.projectName).isEqualTo("NSTL")
        assertThat(mapped.projectHome!!.categoryHome).isFalse()
    }
    @Test fun repeatedSingleWordsEmptyInspectionAndDirectoryTextCannotCreateCategories() {
        for (artifact in listOf(file("invoice invoice invoice"), file(""), file().copy(isDirectory = true))) {
            assertThat(DocumentTopicClassifier(DocumentTopicRules.Defaults).propose(artifact, root, null)).isNull()
        }
        assertThat(resolve(file(), topics = DocumentTopicRules()).destinationDirectory).isNull()
        assertThat(DocumentTopicClassifier(DocumentTopicRules.Defaults).propose(file("invoiced and contractually worded"), root, null)).isNull()
    }
    @Test fun competingTopicsStayUnresolvedEvenBesideAProjectCohort() {
        val artifact = file("Invoice\nPayment due\nContract agreement with a clause")
        val decision = resolve(artifact)
        assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        assertThat(decision.destinationDirectory).isNull()
        assertThat(decision.evidence.first().detail).contains("several topics")
        val build = artifact.copy(stableRef = "$root/Download/Lilith-v1.0.apk", displayName = "Lilith-v1.0.apk", extension = "apk", indexedText = "")
        val result = InboxFilingEngine.resolve(listOf(build, artifact), listOf(ProjectHomeCandidate("Lilith", "$root/Documents/Lilith")), emptyList(), emptyList(), emptyList(), root)
        assertThat(result.decisions.last().confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }
    @Test fun reusableRelativeFoldersHonorWorkflowBasesAndStayTypedInsideSelectedTrees() {
        val rules = DocumentTopicRules(listOf(DocumentTopicTemplate("Household", "Home/Receipts", listOf("invoice", "payment due"))))
        val scope = FileRef.Saf("content://provider/tree/grant/document/opaque-inbox")
        val base = FileRef.Saf("content://provider/tree/grant/document/unrelated-opaque-documents")
        val artifact = file().copy(stableRef = "content://provider/tree/grant/document/opaque-file", parentRef = scope.rawValue())
        val result = InboxFilingEngine.resolve(listOf(artifact), emptyList(), emptyList(), emptyList(), emptyList(), scope.rawValue(),
            documentTopics = rules, newTopicRoots = mapOf(artifact.stableRef to base.rawValue()))
        val plan = InboxFilingSafPlanAdapter.build(result, scope, emptyMap(), "Inbox", newHomeRoot = base)
        assertThat(plan.operations.filterIsInstance<PlannedOperation.Move>().single().destination)
            .isEqualTo(base.child("Home").child("Receipts").child(artifact.displayName))
        assertThat(plan.presentation.groups.single().isTopicDestination).isTrue()
        assertThat(plan.defaultSelectedSourceRefs).isEmpty()
    }
    @Test fun documentCategoriesNeitherOwnAnIntactFolderNorInheritProjectReleasesByTopicName() {
        val child = resolve(file())
        val folderArtifact = file("").copy(stableRef = "$root/Download/bundle", displayName = "bundle", extension = "", isDirectory = true)
        val folder = resolve(folderArtifact)
        val intact = FilingFolderEvidence.reconcile(InboxFilingResult(listOf(folder)), InboxFilingResult(listOf(child)),
            mapOf(child.artifact.stableRef to folderArtifact.stableRef), mapOf(folderArtifact.stableRef to 1), mapOf(folderArtifact.stableRef to 1))
        assertThat(intact.decisions.single().projectHome).isNull()
        val build = file("").copy(stableRef = "$root/Download/Finance-v1.0.apk", displayName = "Finance-v1.0.apk", extension = "apk")
        val result = InboxFilingEngine.resolve(listOf(build, file()), emptyList(), emptyList(), emptyList(), emptyList(), root)
        assertThat(result.decisions.last().release).isNull()
    }
    @Test fun cancellationStopsALargeTopicPassWithoutReturningAProposal() {
        var checks = 0
        val sources = List(16_000) { index -> file().copy(stableRef = "$root/Download/$index.pdf") }
        try {
            InboxFilingEngine.resolve(sources, emptyList(), emptyList(), emptyList(), emptyList(), root,
                checkCancelled = { if (++checks == 200) throw kotlinx.coroutines.CancellationException("Stop review") })
            throw AssertionError("A cancelled pass must not return a proposal")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            assertThat(cancel.message).isEqualTo("Stop review")
            assertThat(checks).isEqualTo(200)
        }
    }

}
