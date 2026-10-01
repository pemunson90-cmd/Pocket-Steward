package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class InboxFilingEngineTest {
    @Test fun explicitProjectFieldCanResolveAGenericFileWithoutARegisteredHome() {
        val file = artifact("ChatGPT_export_1.txt", "txt", 1000).copy(indexedText = "Project: NSTL\nManuscript\nChapter One")
        val decision = InboxFilingEngine.resolve(listOf(file), emptyList(), emptyList(), emptyList(), emptyList(),
            "/storage/emulated/0").decisions.single()
        assertThat(decision.projectHome?.path).isEqualTo("/storage/emulated/0/Documents/NSTL")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.STRONG)
        assertThat(decision.createsProjectHome).isTrue()
    }

    @Test fun conflictingExplicitProjectFieldsStayUnresolved() {
        val file = artifact("export.txt", "txt", 1000).copy(indexedText = "Project: Lilith\nProject: NSTL")
        val decision = InboxFilingEngine.resolve(listOf(file), emptyList(), emptyList(), emptyList(), emptyList(),
            "/storage/emulated/0").decisions.single()
        assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    @Test fun genericHeadingAndIncidentalMentionsCannotInventAProjectHome() {
        for (text in listOf("# Chapter One\nSomeone mentioned Lilith.", "Project: Untitled", "Project: ../Lilith")) {
            val file = artifact("export.txt", "txt", 1000).copy(indexedText = text)
            val decision = InboxFilingEngine.resolve(listOf(file), emptyList(), emptyList(), emptyList(), emptyList(),
                "/storage/emulated/0").decisions.single()
            assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
        }
    }

    @Test fun rememberedOwnerWinsOverUnrelatedFilenameAndContentSignals() {
        val lilithHome = ProjectHomeCandidate("Lilith", "/storage/emulated/0/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val nstlHome = lilithHome.copy(name = "NSTL", path = "/storage/emulated/0/Documents/NSTL")
        val file = artifact("Lilith_notes.txt", "txt", 1000).copy(indexedText = "# Lilith Notes\nProject: Lilith")
        val decision = InboxFilingEngine.resolve(listOf(file), listOf(lilithHome, nstlHome), emptyList(), emptyList(),
            listOf(CorrectionRule("Lilith", "NSTL")), "/storage/emulated/0").decisions.single()
        assertThat(decision.projectHome?.path).isEqualTo(nstlHome.path)
    }

    @Test fun competingRememberedOwnersStayUnresolvedRegardlessOfOtherWeights() {
        val file = artifact("Lilith_NSTL_notes.txt", "txt", 1000).copy(indexedText = "Project: Lilith")
        val decision = InboxFilingEngine.resolve(listOf(file), emptyList(), emptyList(), emptyList(),
            listOf(CorrectionRule("Lilith", "Lilith"), CorrectionRule("NSTL", "NSTL")), "/storage/emulated/0").decisions.single()
        assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    @Test fun aRememberedProjectTitleCannotChooseBetweenTwoMatchingHomes() {
        val one = ProjectHomeCandidate("Lilith", "/storage/emulated/0/Documents/Lilith", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES)
        val two = one.copy(path = "/storage/emulated/0/Projects/Lilith")
        val file = artifact("Lilith_notes.txt", "txt", 1000)
        val decision = InboxFilingEngine.resolve(listOf(file), listOf(one, two), emptyList(), emptyList(),
            listOf(CorrectionRule("Lilith", "Lilith")), "/storage/emulated/0").decisions.single()
        assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    private val lilith = ProjectHomeCandidate(
        name = "Lilith Companion App",
        path = "/storage/emulated/0/Lilith Companion App",
        aliases = listOf("Lilith Companion", "LilithCompanion"),
        packageIds = listOf("com.example.lilithcompanion"),
        hierarchy = ProjectHierarchyStrategy.VERSIONED,
        persisted = true,
    )

    @Test
    fun genericFilenameUsesExplicitDocumentHeadingForProjectAndRole() {
        val home = ProjectHomeCandidate("NSTL", "/storage/emulated/0/Documents/NSTL", hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, persisted = true)
        val file = artifact("final.txt", "txt", 1000).copy(indexedText = "# NSTL Manuscript\nChapter One")
        val result = InboxFilingEngine.resolve(listOf(file), listOf(home), emptyList(), emptyList(), emptyList(), "/storage/emulated/0").decisions.single()
        assertThat(result.confidence).isEqualTo(FilingConfidence.STRONG)
        assertThat(result.destinationDirectory).isEqualTo("${home.path}/Manuscript")
    }

    @Test
    fun incidentalContentMentionIsNotPromotedToStrongTitleEvidence() {
        val home = ProjectHomeCandidate("NSTL", "/storage/emulated/0/Documents/NSTL", persisted = true)
        val file = artifact("final.txt", "txt", 1000).copy(indexedText = "A letter about other matters.\nSomeone mentioned NSTL in passing.")
        val result = InboxFilingEngine.resolve(listOf(file), listOf(home), emptyList(), emptyList(), emptyList(), "/storage/emulated/0").decisions.single()
        assertThat(result.confidence).isNotEqualTo(FilingConfidence.STRONG)
    }

    @Test
    fun newMixedProjectFamilySharesHomeAcrossRoles() {
        val result = InboxFilingEngine.resolve(
            listOf(artifact("NSTL-manuscript.txt", "txt", 1000), artifact("NSTL-notes.md", "md", 1000), artifact("NSTL-cover.jpg", "jpg", 1000)),
            emptyList(), emptyList(), emptyList(), emptyList(), "/storage/emulated/0",
        )
        assertThat(result.decisions.map { it.projectHome?.path }.distinct()).containsExactly("/storage/emulated/0/Documents/NSTL")
        assertThat(result.decisions.map { it.destinationDirectory }).containsExactly(
            "/storage/emulated/0/Documents/NSTL/Manuscript", "/storage/emulated/0/Documents/NSTL/Notes", "/storage/emulated/0/Documents/NSTL/Images",
        )
        assertThat(result.decisions.all { it.confidence == FilingConfidence.STRONG }).isTrue()
    }

    @Test
    fun filenameTopicSuggestsLandscapeWithoutAutomaticSelection() {
        val decision = resolve(artifact("mountain-landscape.jpg", "jpg", 1000)).decisions.single()
        assertThat(decision.destinationDirectory).isEqualTo("/storage/emulated/0/Images/Landscape")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.PROBABLE)
    }

    @Test
    fun conflictingImageTopicsWaitAtCheckpoint() {
        val decision = resolve(artifact("portrait-landscape.jpg", "jpg", 1000)).decisions.single()
        assertThat(decision.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    @Test
    fun projectEvidenceTakesPriorityOverImageTopic() {
        val decision = resolve(artifact("LilithCompanion-landscape.jpg", "jpg", 1000)).decisions.single()
        assertThat(decision.projectHome?.path).isEqualTo(lilith.path)
        assertThat(decision.destinationDirectory).doesNotContain("/Images/Landscape")
    }

    @Test
    fun apkMetadataAndRegisteredPackageFilesIntoExistingVersionHome() {
        val result = resolve(
            FilingArtifact(
                stableRef = "/Download/release.apk",
                displayName = "release.apk",
                extension = "apk",
                sizeBytes = 10,
                createdAt = 1_000,
                modifiedAt = 1_000,
                parentRef = "/Download",
                apkPackageName = "com.example.lilithcompanion",
                apkVersionName = "0.3.105",
                apkLabel = "Lilith Companion",
            ),
        )

        val decision = result.decisions.single()
        assertThat(decision.confidence).isEqualTo(FilingConfidence.STRONG)
        assertThat(decision.projectName).isEqualTo("Lilith Companion App")
        assertThat(decision.release).isEqualTo("0.3.105")
        assertThat(decision.destinationDirectory)
            .isEqualTo("/storage/emulated/0/Lilith Companion App/0.3.105")
    }

    @Test
    fun heterogeneousReleaseArtifactsConvergeOnSameProjectAndVersion() {
        val artifacts = listOf(
            artifact("LilithCompanion-0.3.105-SOURCE.zip", "zip", 2_000),
            artifact("LilithCompanion-0.3.105-notes.md", "md", 2_100),
        )
        val result = InboxFilingEngine.resolve(
            artifacts = artifacts,
            persistedHomes = listOf(lilith),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        assertThat(result.proposed).hasSize(2)
        assertThat(result.proposed.map { it.destinationDirectory }.distinct())
            .containsExactly("/storage/emulated/0/Lilith Companion App/0.3.105")
    }

    @Test
    fun sameProjectDifferentVersionsDoNotCollapseTogether() {
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                artifact("LilithCompanion-0.3.104.apk", "apk", 1_000),
                artifact("LilithCompanion-0.3.105.apk", "apk", 2_000),
            ),
            persistedHomes = listOf(lilith),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        assertThat(result.proposed.mapNotNull { it.release }).containsExactly("0.3.104", "0.3.105")
    }

    @Test
    fun genericUnrelatedFileStaysUnresolved() {
        val result = resolve(artifact("receipt.pdf", "pdf", 50_000))
        assertThat(result.unresolved).hasSize(1)
        assertThat(result.proposed).isEmpty()
    }

    @Test
    fun overlappingProjectNamesRequireReviewInsteadOfChoosingByListOrder() {
        val stories = ProjectHomeCandidate(
            name = "Stories",
            path = "/storage/emulated/0/Documents/Stories",
            persisted = true,
        )
        val nstlStories = ProjectHomeCandidate(
            name = "NSTL Stories",
            path = "/storage/emulated/0/Documents/NSTL Stories",
            persisted = true,
        )
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                artifact("draft.zip", "zip", 1_000).copy(
                    archiveSample = listOf("NSTL Stories/chapter.txt", "Stories/notes.txt"),
                ),
            ),
            persistedHomes = listOf(stories, nstlStories),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        assertThat(result.proposed).isEmpty()
        assertThat(result.unresolved.single().evidenceSummary).contains("matches both")
    }

    @Test
    fun supportingImageNearUniqueStrongReleaseIsOnlyProbable() {
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                FilingArtifact(
                    stableRef = "/Download/LilithCompanion-0.3.105.apk",
                    displayName = "LilithCompanion-0.3.105.apk",
                    extension = "apk",
                    sizeBytes = 10,
                    createdAt = 100_000,
                    modifiedAt = 100_000,
                    parentRef = "/Download",
                    apkPackageName = "com.example.lilithcompanion",
                    apkVersionName = "0.3.105",
                ),
                artifact("IMG_7832.png", "png", 100_500),
            ),
            persistedHomes = listOf(lilith),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        val image = result.decisions.single { it.artifact.displayName == "IMG_7832.png" }
        assertThat(image.confidence).isEqualTo(FilingConfidence.PROBABLE)
        assertThat(image.projectName).isEqualTo("Lilith Companion App")
        assertThat(image.release).isEqualTo("0.3.105")
    }

    @Test
    fun supportingImageIsNotPulledIntoAmbiguousTwoProjectBurst() {
        val mothership = ProjectHomeCandidate(
            name = "Mothership",
            path = "/storage/emulated/0/Mothership",
            packageIds = listOf("com.example.mothership"),
            persisted = true,
        )
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                FilingArtifact(
                    stableRef = "/Download/l.apk",
                    displayName = "l.apk",
                    extension = "apk",
                    sizeBytes = 10,
                    createdAt = 100_000,
                    modifiedAt = 100_000,
                    parentRef = "/Download",
                    apkPackageName = "com.example.lilithcompanion",
                    apkVersionName = "0.3.105",
                ),
                FilingArtifact(
                    stableRef = "/Download/m.apk",
                    displayName = "m.apk",
                    extension = "apk",
                    sizeBytes = 10,
                    createdAt = 100_100,
                    modifiedAt = 100_100,
                    parentRef = "/Download",
                    apkPackageName = "com.example.mothership",
                    apkVersionName = "0.3.0",
                ),
                artifact("IMG_7832.png", "png", 100_200),
            ),
            persistedHomes = listOf(lilith, mothership),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        val image = result.decisions.single { it.artifact.displayName == "IMG_7832.png" }
        assertThat(image.confidence).isEqualTo(FilingConfidence.UNRESOLVED)
    }

    @Test
    fun repeatedUnknownProjectFamilyCanCreateReviewedHome() {
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                artifact("PocketWidget-1.2.0.apk", "apk", 1_000),
                artifact("PocketWidget-1.2.0-SOURCE.zip", "zip", 1_010),
            ),
            persistedHomes = emptyList(),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )

        assertThat(result.proposed).hasSize(2)
        assertThat(result.proposed.first().projectHome?.path).isEqualTo("/storage/emulated/0/Documents/Pocket Widget")
        assertThat(result.proposed.first().createsProjectHome).isTrue()
    }

    @Test
    fun projectDocumentsAndArtworkStayUnderOneHomeWithClearRoles() {
        val nstl = ProjectHomeCandidate(
            name = "NSTL",
            path = "/storage/emulated/0/Documents/NSTL",
            hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES,
            persisted = true,
        )
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(
                artifact("NSTL-manuscript.pdf", "pdf", 1_000),
                artifact("NSTL-draft.txt", "txt", 1_100),
                artifact("NSTL-notes.md", "md", 1_200),
                artifact("NSTL-cover.png", "png", 1_300),
                artifact("NSTL-0.2.0.apk", "apk", 1_400),
                artifact("NSTL-sources.zip", "zip", 1_500),
            ),
            persistedHomes = listOf(nstl),
            discoveredHomes = emptyList(),
            projectKeywords = emptyList(),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )
        assertThat(result.proposed.mapNotNull { it.destinationDirectory }).containsExactly(
            "/storage/emulated/0/Documents/NSTL/Manuscript",
            "/storage/emulated/0/Documents/NSTL/Drafts",
            "/storage/emulated/0/Documents/NSTL/Notes",
            "/storage/emulated/0/Documents/NSTL/Images",
            "/storage/emulated/0/Documents/NSTL/Versions",
            "/storage/emulated/0/Documents/NSTL/Archive",
        )
    }

    @Test
    fun explicitProjectKeywordOutranksTypeCategory() {
        val result = InboxFilingEngine.resolve(
            artifacts = listOf(artifact("Lilith-0.3.105-build-notes.md", "md", 1_000)),
            persistedHomes = listOf(lilith),
            discoveredHomes = emptyList(),
            projectKeywords = listOf(ProjectKeyword("Lilith", "Lilith Companion App")),
            correctionRules = emptyList(),
            storageRoot = "/storage/emulated/0",
        )
        assertThat(result.proposed.single().projectName).isEqualTo("Lilith Companion App")
    }

    @Test
    fun unsafeHierarchySegmentIsRejected() {
        assertThat(InboxFilingEngine.sanitizeSegment("../escape")).isNull()
        assertThat(InboxFilingEngine.sanitizeSegment("good/name")).isNull()
        assertThat(InboxFilingEngine.sanitizeSegment("0.3.105")).isEqualTo("0.3.105")
    }

    private fun resolve(artifact: FilingArtifact): InboxFilingResult = InboxFilingEngine.resolve(
        artifacts = listOf(artifact),
        persistedHomes = listOf(lilith),
        discoveredHomes = emptyList(),
        projectKeywords = emptyList(),
        correctionRules = emptyList<CorrectionRule>(),
        storageRoot = "/storage/emulated/0",
    )

    private fun artifact(name: String, extension: String, time: Long) = FilingArtifact(
        stableRef = "/Download/$name",
        displayName = name,
        extension = extension,
        sizeBytes = 10,
        createdAt = time,
        modifiedAt = time,
        parentRef = "/Download",
    )
}
