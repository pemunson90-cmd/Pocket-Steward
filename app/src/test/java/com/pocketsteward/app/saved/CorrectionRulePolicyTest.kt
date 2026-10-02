package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.filing.*
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.rawValue
import org.junit.Test

class CorrectionRulePolicyTest {
    @Test fun nearestScopeOverridesGlobalRulesAndSiblingPrefixesNeverMatch() {
        val rules = listOf(CorrectionRule("Lilith", "Global"), CorrectionRule("Lilith", "Downloads", "/Download"),
            CorrectionRule("Lilith", "Checkpoint", "/Download/Uncertain"))
        assertThat(CorrectionRulePolicy.matching(rules, "/Download/Uncertain/Lilith.md", "/Download/Uncertain", "Lilith.md").single().destinationFolder).isEqualTo("Checkpoint")
        assertThat(CorrectionRulePolicy.matching(rules, "/Downloads/Lilith.md", "/Downloads", "Lilith.md").single().destinationFolder).isEqualTo("Global")
        assertThat(CorrectionRulePolicy.matching(rules, "/Pictures/Lilith.md", "/Pictures", "Lilithian.md")).isEmpty()
    }
    @Test fun opaqueDocumentUriPrefixesAreNeverUsedAsAncestry() {
        val rule = CorrectionRule("Lilith", "Lilith", "content://provider/document/root")
        assertThat(CorrectionRulePolicy.matching(listOf(rule), "content://provider/document/root/file", "content://provider/document/elsewhere", "Lilith.md")).isEmpty()
        assertThat(CorrectionRulePolicy.matching(listOf(rule), "content://provider/document/99", rule.sourceFolder, "Lilith.md")).containsExactly(rule)
    }
    @Test fun conflictingLearnedOwnersCannotBeOverriddenByFilenameOrCohortEvidence() {
        val homes = listOf("Lilith", "NSTL").map { ProjectHomeCandidate(it, "/Documents/$it", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES) }
        val rules = homes.map { CorrectionRule("Lilith", it.name, "/Download", it.path) }
        val files = listOf(FilingArtifact("/Download/Lilith-notes.txt", "Lilith-notes.txt", "txt", 10, modifiedAt = 100, parentRef = "/Download"),
            FilingArtifact("/Download/NSTL-v1.2.3.apk", "NSTL-v1.2.3.apk", "apk", 10, modifiedAt = 100, parentRef = "/Download"))
        val output = InboxFilingEngine.resolve(files, homes, emptyList(), emptyList(), rules, "/")
        assertThat(output.decisions.first().confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        assertThat(output.decisions.first().destinationDirectory).isNull()
    }
    @Test fun missingRememberedHomeCannotCreateANearDuplicateOrFollowAnUnrelatedCohort() {
        val file = FilingArtifact("/Download/Lilith-notes.txt", "Lilith-notes.txt", "txt", 10, modifiedAt = 100, parentRef = "/Download")
        val rule = CorrectionRule("Lilith", "Lilith", "/Download", "/Volumes/Old/Lilith")
        val result = InboxFilingEngine.resolve(listOf(file), listOf(ProjectHomeCandidate("Lilith", "/Documents/Lilith")), emptyList(), emptyList(), listOf(rule), "/")
        assertThat(result.proposed).isEmpty()
        assertThat(result.unresolved.single().evidenceSummary).contains("unavailable")
    }
    @Test fun onlyExactSelectedDestinationsTeachRulesAndLaterEditsDoNotTeachOldChoices() {
        val source = FileRef.Direct("/Download/Lilith.md")
        val destination = FileRef.Direct("/Documents/Lilith/Lilith.md")
        val rule = CorrectionRule("Lilith", "Lilith", "/Download", "/Documents/Lilith")
        val pending = listOf(PendingCorrection(rule, mapOf(source.rawValue() to destination.rawValue())))
        assertThat(CorrectionApprovalPolicy.approved(pending, emptyList())).isEmpty()
        val move = PlannedOperation.Move(source, destination, "approved")
        assertThat(CorrectionApprovalPolicy.approved(pending, listOf(move))).containsExactly(rule)
        assertThat(CorrectionApprovalPolicy.approved(pending, listOf(move.copy(destination = FileRef.Direct("/Download/Uncertain/Lilith.md"))))).isEmpty()
        assertThat(CorrectionApprovalPolicy.approved(pending, listOf(PlannedOperation.Copy(source, destination, "keep original")))).containsExactly(rule)
    }
}
