package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import org.junit.Test

class InboxFilingEngineTest {
    private val lilith = ProjectHomeCandidate(
        name = "Lilith Companion App",
        path = "/storage/emulated/0/Lilith Companion App",
        aliases = listOf("Lilith Companion", "LilithCompanion"),
        packageIds = listOf("com.example.lilithcompanion"),
        hierarchy = ProjectHierarchyStrategy.VERSIONED,
        persisted = true,
    )

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
        assertThat(result.proposed.first().projectHome?.path).isEqualTo("/storage/emulated/0/Pocket Widget")
        assertThat(result.proposed.first().createsProjectHome).isTrue()
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